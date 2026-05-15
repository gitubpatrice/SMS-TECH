package com.filestech.sms.system.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.filestech.sms.data.mms.MmsDownloader
import com.filestech.sms.di.ApplicationScope
import com.google.android.mms.pdu.NotificationInd
import com.google.android.mms.pdu.PduParser
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Receives MMS WAP_PUSH notifications. The intent carries the binary `m-notification.ind` PDU
 * in the `data` byte[] extra. We parse it to extract the MMSC `contentLocation` URL, then ask
 * [MmsDownloader] to trigger SmsManager.downloadMultimediaMessage. The actual message payload
 * arrives later via [MmsDownloadedReceiver].
 *
 * Audit notes:
 *   - Malformed / truncated PDUs simply log a warning and bail (no crash).
 *   - We DO NOT auto-download messages larger than [MAX_AUTO_DOWNLOAD_BYTES] (default 1 MB) to
 *     guard against runaway data usage — the user can re-trigger from the conversation later.
 *     For SMS Tech's voice-only flow we should never hit that ceiling (clips capped at 300 KB).
 */
@AndroidEntryPoint
class MmsWapPushReceiver : BroadcastReceiver() {

    @Inject lateinit var downloader: MmsDownloader
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.WAP_PUSH_DELIVER") return
        val pdu = intent.getByteArrayExtra("data")
        if (pdu == null || pdu.isEmpty()) {
            Timber.w("WAP_PUSH intent missing PDU data")
            return
        }
        val pending = goAsync()
        scope.launch {
            try {
                val parsed = runCatching { PduParser(pdu).parse() }.getOrNull()
                if (parsed !is NotificationInd) {
                    Timber.w("WAP_PUSH PDU is not a NotificationInd (parsed=%s)", parsed?.javaClass?.simpleName)
                    return@launch
                }
                val contentLocation = parsed.contentLocation?.let { String(it) }
                if (contentLocation.isNullOrBlank()) {
                    Timber.w("NotificationInd has no contentLocation")
                    return@launch
                }
                val size = parsed.messageSize
                if (size in 1..MAX_AUTO_DOWNLOAD_BYTES) {
                    val transactionId = parsed.transactionId?.let { String(it) }
                    val sender = parsed.from?.string
                    val res = downloader.download(contentLocation, transactionId, sender)
                    Timber.i("MMS auto-download triggered loc=%s size=%d outcome=%s",
                        contentLocation, size, res)
                } else {
                    Timber.w("MMS auto-download skipped (size=%d > %d)", size, MAX_AUTO_DOWNLOAD_BYTES)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** 1 MiB safety ceiling. SMS Tech voice clips are ≤ 300 KB, this is a defence in depth. */
        private const val MAX_AUTO_DOWNLOAD_BYTES: Long = 1024L * 1024L
    }
}
