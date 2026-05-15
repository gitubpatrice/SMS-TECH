package com.filestech.sms.data.mms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
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
 *
 * v1.2.4 refactor: the voice + media send paths used to be two near-identical 70-line blocks
 * of cleanup-on-each-failure. They now share a single [dispatchMms] private engine — public
 * `sendVoiceMms` / `sendMediaMms` are thin wrappers that differ only in input validation and
 * which `MmsBuilder` overload they pick for PDU encoding.
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
        val attachments = listOf(
            MmsBuilder.MmsAttachment(audioFile, mimeType, MmsBuilder.MmsAttachment.Kind.AUDIO),
        )
        dispatchMms(
            localMessageId = localMessageId,
            recipients = recipients,
            attachments = attachments,
            textBody = null,
            subId = subId,
            encodePdu = {
                builder.buildVoiceSendReq(
                    audioFile = audioFile,
                    mimeType = mimeType,
                    recipients = recipients,
                    requestDeliveryReport = requestDeliveryReport,
                )
            },
        )
    }

    /**
     * Generic media-MMS dispatch — works with any combination of audio / image / video / file
     * parts + an optional text body. Calls into [MmsBuilder.buildMultipartSendReq] which already
     * knows how to encode the multipart SMIL for non-voice MIME types and which uses reflection
     * compat for Samsung One UI 6+.
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
        dispatchMms(
            localMessageId = localMessageId,
            recipients = recipients,
            attachments = attachments,
            textBody = textBody,
            subId = subId,
            encodePdu = {
                builder.buildMultipartSendReq(
                    attachments = attachments,
                    textBody = textBody,
                    recipients = recipients,
                    requestDeliveryReport = requestDeliveryReport,
                )
            },
        )
    }

    /**
     * The shared dispatch engine for both send paths. Lifecycle:
     *
     *  1. Mirror the outbox row into `content://mms` so the message survives a reinstall even
     *     if the result broadcast never fires (Samsung One UI does not writeback on its own).
     *  2. Encode the PDU via the provided [encodePdu] closure (voice vs multipart).
     *  3. Persist the PDU bytes to a FileProvider-mapped cache file.
     *  4. Build the FileProvider URI that the OS reads from.
     *  5. Wire the result PendingIntent (explicit-class so the receiver stays `exported=false`).
     *  6. Hand off to `SmsManager.sendMultimediaMessage`.
     *
     * Every failure path rolls back: deletes the cache file if it was created, deletes the
     * system OUTBOX row if we inserted one. Caller gets a typed [Outcome.Failure].
     */
    private suspend fun dispatchMms(
        localMessageId: Long,
        recipients: List<String>,
        attachments: List<MmsBuilder.MmsAttachment>,
        textBody: String?,
        subId: Int?,
        encodePdu: () -> ByteArray?,
    ): Outcome<Unit> {
        val mmsSystemId = systemWriteback.insertOutbox(
            recipients = recipients,
            attachments = attachments,
            textBody = textBody,
        ) ?: -1L

        val pdu = encodePdu() ?: run {
            rollback(mmsSystemId, pduFile = null)
            return Outcome.Failure(AppError.Telephony("PDU encoding failed"))
        }

        val pduFile = writePduFile(pdu).getOrElse { t ->
            Timber.w(t, "Writing MMS PDU file failed")
            rollback(mmsSystemId, pduFile = null)
            return Outcome.Failure(AppError.Storage(t))
        }

        val pduUri = pduFileProviderUri(pduFile).getOrElse { t ->
            Timber.w(t, "FileProvider URI build failed")
            rollback(mmsSystemId, pduFile)
            return Outcome.Failure(AppError.Storage(t))
        }

        val pi = buildSentIntent(localMessageId, pduFile, mmsSystemId)

        return try {
            val sm = subscriptionAwareManager(subId)
            sm.sendMultimediaMessage(context, pduUri, null, null, pi)
            Outcome.Success(Unit)
        } catch (t: Throwable) {
            Timber.w(t, "SmsManager.sendMultimediaMessage failed")
            rollback(mmsSystemId, pduFile)
            Outcome.Failure(AppError.Telephony("sendMultimediaMessage failed", t))
        }
    }

    /** Best-effort rollback of any side-effect we may have already produced. */
    private suspend fun rollback(mmsSystemId: Long, pduFile: File?) {
        pduFile?.let { runCatching { it.delete() } }
        if (mmsSystemId > 0L) systemWriteback.delete(mmsSystemId)
    }

    /**
     * Writes [pdu] bytes to a uniquely-named cache file under `mms_outgoing/`. Caller is
     * responsible for deleting the returned [File] on any subsequent failure (handled by the
     * dispatch engine).
     */
    private fun writePduFile(pdu: ByteArray): Result<File> {
        return runCatching {
            val pduDir = File(context.cacheDir, MMS_OUT_DIR).apply { mkdirs() }
            val pduFile = File(pduDir, "send-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.pdu")
            pduFile.writeBytes(pdu)
            pduFile
        }
    }

    /** Builds the FileProvider URI the OS will use to read the PDU bytes back. */
    private fun pduFileProviderUri(pduFile: File): Result<Uri> = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pduFile)
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
