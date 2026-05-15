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
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Triggers the OS-managed retrieval of an incoming MMS body from the MMSC.
 *
 * Input: the `contentLocation` URL carried by the [com.google.android.mms.pdu.NotificationInd]
 * that arrived via WAP_PUSH. We create an empty cache file, hand its [FileProvider] Uri to
 * [SmsManager.downloadMultimediaMessage], and Android takes care of the rest (APN routing,
 * HTTP GET, writing the [com.google.android.mms.pdu.RetrieveConf] PDU into our file). The
 * result is delivered to [com.filestech.sms.system.receiver.MmsDownloadedReceiver].
 */
@Singleton
class MmsDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun download(
        contentLocation: String,
        transactionId: String?,
        senderAddress: String?,
        subId: Int? = null,
    ): Outcome<Unit> {
        if (contentLocation.isBlank()) return Outcome.Failure(AppError.Validation("empty contentLocation"))

        val dir = File(context.cacheDir, MMS_IN_DIR).apply { mkdirs() }
        val pduFile = File(dir, "in-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.pdu")
        try {
            // Create the file empty; SmsManager writes the downloaded PDU into it.
            pduFile.createNewFile()
        } catch (t: Throwable) {
            Timber.w(t, "Cannot create MMS PDU destination file")
            return Outcome.Failure(AppError.Storage(t))
        }

        val pduUri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pduFile)
        } catch (t: Throwable) {
            pduFile.delete()
            return Outcome.Failure(AppError.Storage(t))
        }

        // Audit P0-2 (v1.2.0): Intent **explicit** via `setClass`. The previous `setPackage`-only
        // implicit form relied on the static receiver having an `<intent-filter>` for the custom
        // action, which it doesn't — Android 14+ silently drops these broadcasts. Without delivery,
        // the receiver never runs → the downloaded PDU file (raw audio + sender headers) stays
        // forever in `cache/mms_incoming/`, leaking plaintext on adb-pull / forensics.
        val intent = Intent(ACTION_MMS_DOWNLOADED)
            .setClass(context, com.filestech.sms.system.receiver.MmsDownloadedReceiver::class.java)
            .apply {
                putExtra(EXTRA_PDU_FILE, pduFile.absolutePath)
                putExtra(EXTRA_TRANSACTION_ID, transactionId)
                putExtra(EXTRA_SENDER, senderAddress)
            }
        val reqCode = (pduFile.absolutePath.hashCode() and 0x7FFFFFFF)
        val pi = PendingIntent.getBroadcast(
            context, reqCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return try {
            subscriptionAwareManager(subId).downloadMultimediaMessage(
                context, contentLocation, pduUri, null, pi,
            )
            Outcome.Success(Unit)
        } catch (t: Throwable) {
            Timber.w(t, "downloadMultimediaMessage failed")
            pduFile.delete()
            Outcome.Failure(AppError.Telephony("downloadMultimediaMessage failed", t))
        }
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
        const val ACTION_MMS_DOWNLOADED: String = "com.filestech.sms.action.MMS_DOWNLOADED"
        const val EXTRA_PDU_FILE: String = "com.filestech.sms.extra.PDU_FILE"
        const val EXTRA_TRANSACTION_ID: String = "com.filestech.sms.extra.TRANSACTION_ID"
        const val EXTRA_SENDER: String = "com.filestech.sms.extra.SENDER"

        private const val MMS_IN_DIR: String = "mms_incoming"
    }
}
