package com.filestech.sms.data.mms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.FileProvider
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dispatches an MMS `m-send.req` PDU via [SmsManager.sendMultimediaMessage] (Android 5+, API 21).
 *
 * Android takes care of the heavy lifting: APN selection, MMSC HTTP POST, transient retries,
 * and PDU response parsing. We only need to:
 *   1. encode the PDU bytes (via [MmsBuilder])
 *   2. drop them into a [FileProvider]-shareable file
 *   3. wire a [PendingIntent] that funnels the result back to [MmsSentReceiver]
 *
 * The PDU file is short-lived — [MmsSentReceiver] deletes it on dispatch completion.
 */
@Singleton
class MmsSender @Inject constructor(
    @ApplicationContext private val context: Context,
    private val builder: MmsBuilder,
    private val systemWriteback: MmsSystemWriteback,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Encodes and dispatches a voice MMS. Returns immediately after [SmsManager] takes ownership
     * — the final SENT/FAILED status arrives asynchronously via [MmsSentReceiver].
     *
     * @param localMessageId the Room id of the outgoing message (passed back via the
     *   PendingIntent so the receiver can update the correct row)
     */
    suspend fun sendVoiceMms(
        localMessageId: Long,
        recipients: List<String>,
        audioFile: File,
        mimeType: String,
        subId: Int? = null,
        requestDeliveryReport: Boolean = false,
    ): Outcome<Unit> = withContext(io) {
        if (recipients.isEmpty()) return@withContext Outcome.Failure(AppError.Validation("no recipients"))
        if (!audioFile.exists() || audioFile.length() == 0L) {
            return@withContext Outcome.Failure(AppError.Validation("audio file missing"))
        }

        // v1.2.2 writeback: insert the system row + parts BEFORE dispatch so the MMS survives
        // an app reinstall even if the result-broadcast never fires. Failure to write back is
        // not fatal — we keep the dispatch going.
        val voiceAttachment = MmsBuilder.MmsAttachment(audioFile, mimeType, MmsBuilder.MmsAttachment.Kind.AUDIO)
        val mmsSystemId = systemWriteback.insertOutbox(
            recipients = recipients,
            attachments = listOf(voiceAttachment),
            textBody = null,
        ) ?: -1L

        // 1) Encode the PDU
        val pdu = builder.buildVoiceSendReq(
            audioFile = audioFile,
            mimeType = mimeType,
            recipients = recipients,
            requestDeliveryReport = requestDeliveryReport,
        ) ?: run {
            if (mmsSystemId > 0L) systemWriteback.delete(mmsSystemId)
            return@withContext Outcome.Failure(AppError.Telephony("PDU encoding failed"))
        }

        // 2) Persist to disk in the FileProvider-mapped folder
        val pduDir = File(context.cacheDir, MMS_OUT_DIR).apply { mkdirs() }
        val pduFile = File(pduDir, "send-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.pdu")
        try {
            pduFile.writeBytes(pdu)
        } catch (t: Throwable) {
            Timber.w(t, "Writing MMS PDU file failed")
            if (mmsSystemId > 0L) systemWriteback.delete(mmsSystemId)
            return@withContext Outcome.Failure(AppError.Storage(t))
        }

        val pduUri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pduFile)
        } catch (t: Throwable) {
            pduFile.delete()
            if (mmsSystemId > 0L) systemWriteback.delete(mmsSystemId)
            Timber.w(t, "FileProvider URI build failed")
            return@withContext Outcome.Failure(AppError.Storage(t))
        }

        // 3) Wire the result PendingIntent — also carries the PDU file path so the receiver can
        //    delete it after dispatch. **Explicit Intent**: we target `MmsSentReceiver` directly
        //    via `setClass`, so we don't depend on an `<intent-filter>` in the manifest. This
        //    is required because (a) Samsung One UI's static-receiver routing is finicky with
        //    custom-action implicit intents inside a package and (b) Android 12+ tightens
        //    rules around exported receivers — an explicit-component PendingIntent keeps the
        //    receiver `exported=false` while still being reachable.
        val pi = buildSentIntent(localMessageId, pduFile, mmsSystemId)

        // 4) Dispatch
        try {
            val sm = subscriptionAwareManager(subId)
            sm.sendMultimediaMessage(context, pduUri, null, null, pi)
            Outcome.Success(Unit)
        } catch (t: Throwable) {
            Timber.w(t, "SmsManager.sendMultimediaMessage failed")
            pduFile.delete()
            if (mmsSystemId > 0L) systemWriteback.delete(mmsSystemId)
            Outcome.Failure(AppError.Telephony("sendMultimediaMessage failed", t))
        }
    }

    /**
     * Generic media-MMS dispatch (v1.2.1) — same pipeline as [sendVoiceMms] but works with any
     * combination of audio / image / video / arbitrary parts + an optional text body. Calls into
     * [MmsBuilder.buildMultipartSendReq] which already knows how to encode the multipart SMIL
     * for non-voice MIME types and which uses reflection compat for Samsung One UI 6+.
     *
     * The same explicit-intent and PDU-cache cleanup contracts as the voice path apply: PDU bytes
     * are written to `cache/mms_outgoing/`, handed to the OS as a FileProvider URI, deleted by
     * [com.filestech.sms.system.receiver.MmsSentReceiver] once the system reports the dispatch
     * outcome.
     */
    suspend fun sendMediaMms(
        localMessageId: Long,
        recipients: List<String>,
        attachments: List<MmsBuilder.MmsAttachment>,
        textBody: String? = null,
        subId: Int? = null,
        requestDeliveryReport: Boolean = false,
    ): Outcome<Unit> = withContext(io) {
        if (recipients.isEmpty()) return@withContext Outcome.Failure(AppError.Validation("no recipients"))
        if (attachments.isEmpty() && textBody.isNullOrBlank()) {
            return@withContext Outcome.Failure(AppError.Validation("no payload"))
        }
        for (a in attachments) {
            if (!a.file.exists() || a.file.length() == 0L) {
                return@withContext Outcome.Failure(AppError.Validation("attachment file missing: ${a.file.name}"))
            }
        }

        // v1.2.2 writeback: same rationale as sendVoiceMms — persist a system-provider row up
        // front so the outgoing media MMS survives a reinstall. The receiver flips to SENT or
        // deletes on the result-broadcast.
        val mmsSystemId = systemWriteback.insertOutbox(
            recipients = recipients,
            attachments = attachments,
            textBody = textBody,
        ) ?: -1L

        val pdu = builder.buildMultipartSendReq(
            attachments = attachments,
            textBody = textBody,
            recipients = recipients,
            requestDeliveryReport = requestDeliveryReport,
        ) ?: run {
            if (mmsSystemId > 0L) systemWriteback.delete(mmsSystemId)
            return@withContext Outcome.Failure(AppError.Telephony("PDU encoding failed"))
        }

        val pduDir = File(context.cacheDir, MMS_OUT_DIR).apply { mkdirs() }
        val pduFile = File(pduDir, "send-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.pdu")
        try {
            pduFile.writeBytes(pdu)
        } catch (t: Throwable) {
            Timber.w(t, "Writing MMS PDU file failed")
            if (mmsSystemId > 0L) systemWriteback.delete(mmsSystemId)
            return@withContext Outcome.Failure(AppError.Storage(t))
        }

        val pduUri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pduFile)
        } catch (t: Throwable) {
            pduFile.delete()
            if (mmsSystemId > 0L) systemWriteback.delete(mmsSystemId)
            Timber.w(t, "FileProvider URI build failed")
            return@withContext Outcome.Failure(AppError.Storage(t))
        }

        val pi = buildSentIntent(localMessageId, pduFile, mmsSystemId)

        try {
            val sm = subscriptionAwareManager(subId)
            sm.sendMultimediaMessage(context, pduUri, null, null, pi)
            Outcome.Success(Unit)
        } catch (t: Throwable) {
            Timber.w(t, "SmsManager.sendMultimediaMessage (media) failed")
            pduFile.delete()
            if (mmsSystemId > 0L) systemWriteback.delete(mmsSystemId)
            Outcome.Failure(AppError.Telephony("sendMultimediaMessage failed", t))
        }
    }

    /**
     * Builds the result-broadcast PendingIntent shared by both send paths. Explicit class
     * routing (vs. an `<intent-filter>` + `setPackage`) keeps the receiver `exported=false`
     * on Android 12+ while still being reachable, and works around Samsung One UI's
     * static-receiver routing quirks.
     */
    private fun buildSentIntent(localMessageId: Long, pduFile: File, mmsSystemId: Long): PendingIntent {
        val intent = Intent(ACTION_MMS_SENT)
            .setClass(context, com.filestech.sms.system.receiver.MmsSentReceiver::class.java)
            .apply {
                putExtra(EXTRA_LOCAL_ID, localMessageId)
                putExtra(EXTRA_PDU_FILE, pduFile.absolutePath)
                putExtra(EXTRA_MMS_SYSTEM_ID, mmsSystemId)
            }
        val requestCode = (localMessageId xor (localMessageId ushr 32)).toInt() and 0x7FFFFFFF
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun subscriptionAwareManager(subId: Int?): SmsManager = when {
        subId == null -> @Suppress("DEPRECATION") SmsManager.getDefault()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            context.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
        else -> @Suppress("DEPRECATION") SmsManager.getSmsManagerForSubscriptionId(
            if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                SubscriptionManager.getDefaultSmsSubscriptionId()
            } else subId,
        )
    }

    companion object {
        const val ACTION_MMS_SENT: String = "com.filestech.sms.action.MMS_SENT"
        const val EXTRA_LOCAL_ID: String = "com.filestech.sms.extra.LOCAL_ID"
        const val EXTRA_PDU_FILE: String = "com.filestech.sms.extra.PDU_FILE"
        const val EXTRA_MMS_SYSTEM_ID: String = "com.filestech.sms.extra.MMS_SYSTEM_ID"

        private const val MMS_OUT_DIR: String = "mms_outgoing"
    }
}
