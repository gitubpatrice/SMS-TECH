package com.filestech.sms.system.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.filestech.sms.data.mms.MmsDownloader
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.di.ApplicationScope
import com.google.android.mms.pdu.PduBody
import com.google.android.mms.pdu.PduParser
import com.google.android.mms.pdu.RetrieveConf
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * Receives the result of [MmsDownloader.download]. The OS has written the binary RetrieveConf
 * PDU into the cache file whose path we passed in the PendingIntent. We parse the PDU, extract
 * the first audio attachment (the only one SMS Tech v1 supports), persist it to a stable cache
 * directory, and mirror the message into Room.
 */
@AndroidEntryPoint
class MmsDownloadedReceiver : BroadcastReceiver() {

    @Inject @ApplicationContext lateinit var appContext: Context
    @Inject lateinit var mirror: ConversationMirror
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MmsDownloader.ACTION_MMS_DOWNLOADED) return
        val pduPath = intent.getStringExtra(MmsDownloader.EXTRA_PDU_FILE)
        val senderHint = intent.getStringExtra(MmsDownloader.EXTRA_SENDER)
        val rc = resultCode
        val pending = goAsync()
        scope.launch {
            try {
                if (rc != Activity.RESULT_OK) {
                    Timber.w("MMS download failed rc=%d path=%s", rc, pduPath)
                    return@launch
                }
                val pduFile = pduPath?.let { File(it) }
                if (pduFile == null || !pduFile.exists() || pduFile.length() == 0L) {
                    Timber.w("MMS PDU missing or empty: %s", pduPath)
                    return@launch
                }
                val bytes = runCatching { pduFile.readBytes() }.getOrNull()
                if (bytes == null) {
                    Timber.w("Cannot read MMS PDU bytes: %s", pduPath)
                    return@launch
                }
                val parsed = runCatching { PduParser(bytes).parse() }.getOrNull()
                if (parsed !is RetrieveConf) {
                    Timber.w("MMS PDU is not RetrieveConf (parsed=%s)", parsed?.javaClass?.simpleName)
                    return@launch
                }

                // Audit M-10: in-memory replay guard. A flaky carrier can deliver the same
                // `m-notification.ind` twice, which the OS dutifully turns into two
                // `RetrieveConf` PDUs — each parsed here would mirror the same MMS twice and
                // surface duplicates in the thread. We dedup by the PDU's `transactionId` for
                // [DEDUP_TTL_MS] (5 min): replays in the wild always come back within seconds,
                // and the bounded set means we cap the singleton's memory footprint.
                //
                // Limit: a process restart loses the set, so a replay split across the kill
                // boundary still produces 2 rows. A persistent fix would require a Room migration
                // (new `transaction_id` column on `messages`), deferred to v1.1.1.
                val txId = parsed.transactionId?.toString(Charsets.UTF_8)
                if (!txId.isNullOrEmpty()) {
                    val now = System.currentTimeMillis()
                    synchronized(processedTransactions) {
                        processedTransactions.entries.removeAll { now - it.value > DEDUP_TTL_MS }
                        if (processedTransactions.containsKey(txId)) {
                            Timber.i("MMS replay suppressed: txId=%s", txId)
                            return@launch
                        }
                        processedTransactions[txId] = now
                    }
                }

                val sender = parsed.from?.string?.let(::stripMmsAddressSuffix) ?: senderHint ?: ""
                val date = (if (parsed.date > 0) parsed.date * 1000L else System.currentTimeMillis())
                val subject = parsed.subject?.string?.takeIf { it.isNotBlank() }

                val audio = parsed.body?.let(::extractFirstAudioPart)
                val audioFile = audio?.let { persistAudio(it.first, it.second) }
                val mime = audio?.second
                val body = subject ?: defaultBodyForAudio(audioFile != null)

                mirror.upsertIncomingMms(
                    address = sender,
                    audioFile = audioFile,
                    mimeType = mime,
                    durationMs = null,
                    body = body,
                    date = date,
                )
            } catch (t: Throwable) {
                Timber.w(t, "MMS download handling failed")
            } finally {
                // Cleanup of the temp PDU file is best-effort — keep it on failure so debugging is possible.
                if (rc == Activity.RESULT_OK && !pduPath.isNullOrBlank()) {
                    runCatching { File(pduPath).delete() }
                }
                pending.finish()
            }
        }
    }

    /**
     * Returns the (bytes, mimeType) of the first audio part in [body], or null if none is found.
     * SMS Tech v1 only renders one audio per MMS — we ignore any extra parts.
     */
    private fun extractFirstAudioPart(body: PduBody): Pair<ByteArray, String>? {
        val n = body.partsNum
        for (i in 0 until n) {
            val part = body.getPart(i) ?: continue
            val ct = part.contentType?.let { String(it) } ?: continue
            if (ct.startsWith("audio/", ignoreCase = true)) {
                val data = part.data ?: continue
                if (data.isNotEmpty()) return data to ct
            }
        }
        return null
    }

    /** Writes the audio bytes to a stable cache file the AttachmentEntity will reference. */
    private fun persistAudio(bytes: ByteArray, mime: String): File? = try {
        val dir = File(appContext.cacheDir, INCOMING_AUDIO_DIR).apply { mkdirs() }
        val ext = mimeExtension(mime)
        val f = File(dir, "in-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.$ext")
        f.writeBytes(bytes)
        f
    } catch (t: Throwable) {
        Timber.w(t, "persistAudio failed")
        null
    }

    private fun mimeExtension(mime: String): String = when (mime.lowercase()) {
        "audio/mp4", "audio/aac", "audio/mp4a-latm" -> "m4a"
        "audio/amr", "audio/3gpp" -> "amr"
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/ogg", "audio/opus" -> "ogg"
        else -> "bin"
    }

    /**
     * Strips the "/TYPE=PLMN" or similar suffix MMS gateways append to phone numbers in the
     * From: header (cf. [com.filestech.sms.data.mms.MmsBuilder.formatAddressForMms]).
     */
    private fun stripMmsAddressSuffix(raw: String): String {
        val idx = raw.indexOf('/')
        return if (idx > 0) raw.substring(0, idx) else raw
    }

    private fun defaultBodyForAudio(hasAudio: Boolean): String =
        if (hasAudio) "🎤" else "[MMS]"

    private companion object {
        const val INCOMING_AUDIO_DIR: String = "mms_incoming_audio"
        /** Audit M-10: TTL for the in-memory dedup set. 5 min covers real-world carrier replays. */
        const val DEDUP_TTL_MS: Long = 5 * 60 * 1_000L
        /**
         * Process-wide cache of recently-mirrored MMS transaction IDs. Backed by a synchronized
         * [HashMap] (the receiver is `AndroidEntryPoint`-stateless from Hilt's perspective, so
         * a top-level `companion object` field is the right scope: shared across receiver
         * instantiations within the same process). Bounded by [DEDUP_TTL_MS]: stale entries get
         * pruned on the next entry.
         */
        @JvmStatic
        private val processedTransactions = HashMap<String, Long>()
    }
}
