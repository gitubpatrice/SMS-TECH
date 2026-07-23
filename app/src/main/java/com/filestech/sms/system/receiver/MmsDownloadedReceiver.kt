package com.filestech.sms.system.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SubscriptionManager
import com.filestech.sms.core.ext.stripInvisibleChars
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.mms.MmsDownloader
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.di.ApplicationScope
import com.filestech.sms.pdu.CharacterSets
import com.filestech.sms.pdu.PduBody
import com.filestech.sms.pdu.PduParser
import com.filestech.sms.pdu.RetrieveConf
import com.filestech.sms.system.notifications.IncomingMessageNotifier
import com.filestech.sms.system.notifications.MmsFailureNotifier
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.UUID

/**
 * Receives the result of [MmsDownloader.download]. The OS has written the binary RetrieveConf
 * PDU into the cache file whose path we passed in the PendingIntent. We parse the PDU, extract
 * the first non-presentation media part (image, audio, video, file), persist it to a stable
 * cache directory, and mirror the message into Room.
 *
 * **v1.3.10** :
 *   - No more `@AndroidEntryPoint`. Same root cause as [MmsWapPushReceiver]: silent Hilt
 *     injection crash on Android 10 OEM ROMs when the receiver is dispatched at cold-start.
 *     [EntryPointAccessors.fromApplication] is resolved on-demand inside [onReceive].
 *   - First MMS part of MIME image, video, audio, or application (except application/smil)
 *     is taken as the attachment. SMS Tech v1.3.9 only kept audio, which dropped every
 *     incoming image/file MMS to a placeholder `[MMS]` bubble.
 *   - Optional text/plain part is used as caption body (preferred over the MMS Subject
 *     header, which gateways often leave blank).
 */
class MmsDownloadedReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface MmsDownloadedEntryPoint {
        fun mirror(): ConversationMirror
        fun messageDao(): MessageDao
        fun notifier(): IncomingMessageNotifier
        // Audit R2 (v1.14.8) — Notification user lorsque le download MMS échoue (rc != OK).
        fun mmsFailureNotifier(): MmsFailureNotifier

        @ApplicationScope
        fun applicationScope(): CoroutineScope
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MmsDownloader.ACTION_MMS_DOWNLOADED) return
        val pduPath = intent.getStringExtra(MmsDownloader.EXTRA_PDU_FILE)
        val senderHint = intent.getStringExtra(MmsDownloader.EXTRA_SENDER)
        // v1.22.0 (fix double SIM) — SIM d'arrivée propagée par [MmsDownloader]. Encodée
        // INVALID_SUBSCRIPTION_ID quand inconnue → retraduite en `null` (colonne `sub_id`
        // laissée nulle, comme les MMS reçus avant cette version).
        val subId = intent
            .getIntExtra(MmsDownloader.EXTRA_SUBSCRIPTION_ID, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
            .takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
        val rc = resultCode
        val appContext = context.applicationContext

        val entry = try {
            EntryPointAccessors.fromApplication(
                appContext,
                MmsDownloadedEntryPoint::class.java,
            )
        } catch (t: Throwable) {
            Timber.e(t, "Hilt entry point resolution failed in MmsDownloadedReceiver")
            return
        }
        // v1.24.0 SEC-CRIT — `entry.mirror()` / `entry.messageDao()` provisionnent `AppDatabase`,
        // donc la réparation zéro-clé. Résoudre ici les exécutait sur le main thread d'`onReceive`,
        // sous un timeout ANR de broadcast de 10 s. Seul le scope est résolu en amont : il n'ouvre
        // aucune base.
        val scope = entry.applicationScope()

        val pending = goAsync()
        scope.launch {
            val mirror = entry.mirror()
            val messageDao = entry.messageDao()
            val notifier = entry.notifier()
            val failureNotifier = entry.mmsFailureNotifier()
            try {
                if (rc != Activity.RESULT_OK) {
                    // Audit R2 (v1.14.8) — avant : log + return silencieux, l'user ne savait
                    // pas qu'un MMS lui était destiné. Maintenant on poste une notification
                    // sur le canal FAILED pour l'inviter à vérifier le signal et retry depuis
                    // l'app système (ou ressayer plus tard). `senderHint` peut être null si
                    // MmsDownloader n'a pas pu l'extraire — le notifier fallback alors sur
                    // "un contact inconnu".
                    Timber.w("MMS download failed rc=%d path=%s", rc, pduPath)
                    failureNotifier.notifyFailure(
                        reason = MmsFailureNotifier.Reason.DOWNLOAD_FAILED,
                        senderAddress = senderHint,
                    )
                    return@launch
                }
                val pduFile = pduPath?.let { File(it) }
                if (pduFile == null || !pduFile.exists() || pduFile.length() == 0L) {
                    Timber.w("MMS PDU missing or empty: %s", pduPath)
                    return@launch
                }
                // v1.3.10 (SEC-04) — sandbox check: the EXTRA_PDU_FILE path is set by
                // [MmsDownloader] inside our own process and the receiver is `exported=false`,
                // so a malicious external sender cannot reach this code with a forged path
                // today. We still canonicalize + verify the resolved file lives inside our
                // `cacheDir/mms_incoming/` sandbox so an accidental future regression (a new
                // intent-filter, an `exported` flip, a test helper) cannot turn this receiver
                // into a "read any file the app can see" primitive.
                val sandboxDir = runCatching {
                    File(appContext.cacheDir, MmsDownloader.MMS_IN_DIR).canonicalFile
                }.getOrNull()
                val canonicalPdu = runCatching { pduFile.canonicalFile }.getOrNull()
                if (sandboxDir == null || canonicalPdu == null ||
                    !canonicalPdu.toPath().startsWith(sandboxDir.toPath())
                ) {
                    Timber.w("MMS PDU path outside sandbox: %s", pduPath)
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

                // v1.6.1 (audit SEC-08) — strip Bidi/RLO/ZWSP sur sender + subject +
                // caption avant d'arriver dans la notification système. Le PDU MMS est
                // une entrée externe non-contrôlée ; sans cette sanitization un
                // expéditeur malicieux pouvait inverser visuellement le preview notif
                // (parité avec le path SMS qui appelle déjà stripInvisibleChars dans
                // SmsDeliverReceiver).
                val sender = (parsed.from?.string?.let(::stripMmsAddressSuffix) ?: senderHint ?: "")
                    .stripInvisibleChars()
                val date = (if (parsed.date > 0) parsed.date * 1000L else System.currentTimeMillis())
                val subject = parsed.subject?.string?.stripInvisibleChars()?.takeIf { it.isNotBlank() }

                val body = parsed.body
                val media = body?.let(::extractFirstMediaPart)
                val mediaFile = media?.let { persistAttachment(appContext, it.first, it.second) }
                val mime = media?.second
                val caption = body?.let(::extractFirstTextCaption)?.stripInvisibleChars()
                // `previewLabel` is the conversation-list line + notification text. It falls back
                // to the Subject header, then to a mime-derived placeholder, so the user always
                // sees something meaningful. `caption` (the raw user text) is what gets stored
                // in `messages.body` and rendered as an inline caption below the attachment.
                val previewLabel = caption ?: subject ?: defaultPreviewLabel(mime)

                val msgId = mirror.upsertIncomingMms(
                    address = sender,
                    attachmentFile = mediaFile,
                    mimeType = mime,
                    durationMs = null,
                    caption = caption,
                    previewLabel = previewLabel,
                    date = date,
                    subId = subId,
                )
                // Symmetric with [SmsDeliverReceiver]: re-fetch the row to get the conversationId
                // so [IncomingMessageNotifier.cancelAllForConversation] can later clear the
                // notification by tag when the user opens the thread.
                val convId = messageDao.findById(msgId)?.conversationId
                if (convId != null) {
                    notifier.notifyIncoming(
                        address = sender,
                        body = previewLabel,
                        messageId = msgId,
                        conversationId = convId,
                    )
                } else {
                    Timber.w("MmsDownloadedReceiver: message %d not found after insert", msgId)
                }
            } catch (t: Throwable) {
                Timber.w(t, "MMS download handling failed")
            } finally {
                if (rc == Activity.RESULT_OK && !pduPath.isNullOrBlank()) {
                    runCatching { File(pduPath).delete() }
                }
                pending.finish()
            }
        }
    }

    /**
     * Decodes a part's content-type bytes. WAP text-strings carry a trailing NUL that
     * `String(bytes)` would keep, breaking equality / prefix checks ("text/plain " !=
     * "text/plain"). Also trims content-type parameters (`; charset=...`) so callers can do
     * simple prefix / equality on the bare MIME.
     */
    private fun decodeMime(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        val end = bytes.indexOf(0.toByte()).let { if (it < 0) bytes.size else it }
        if (end == 0) return null
        return String(bytes, 0, end)
            .substringBefore(';')
            .trim()
            .lowercase()
            .takeIf { it.isNotEmpty() }
    }

    /**
     * Returns the (bytes, mimeType) of the first non-presentation media part in [body], or null.
     * Skips application/smil (the layout descriptor) and text parts (captions go through
     * [extractFirstTextCaption]). SMS Tech v1 only renders one attachment per MMS — any extras
     * are ignored.
     */
    private fun extractFirstMediaPart(body: PduBody): Pair<ByteArray, String>? {
        val n = body.partsNum
        for (i in 0 until n) {
            val part = body.getPart(i) ?: continue
            val ct = decodeMime(part.contentType) ?: continue
            if (ct.startsWith("text/") || ct == "application/smil") continue
            val data = part.data ?: continue
            if (data.isNotEmpty()) return data to ct
        }
        return null
    }

    /**
     * Returns the trimmed text of the first `text/plain` part, if any.
     *
     * Charset resolution: the WAP "any-charset" sentinel (MIBenum 0, mapped to the literal `*`
     * in [CharacterSets]) is not a valid JVM charset — calling `charset("*")` would throw and
     * silently drop the caption. We also treat the absence of any usable Java charset as
     * UTF-8, which matches what every modern Android sender produces.
     */
    private fun extractFirstTextCaption(body: PduBody): String? {
        val n = body.partsNum
        for (i in 0 until n) {
            val part = body.getPart(i) ?: continue
            val ct = decodeMime(part.contentType) ?: continue
            if (ct != "text/plain") continue
            val data = part.data ?: continue
            if (data.isEmpty()) continue
            val javaCharset = resolveCharset(part.charset)
            val text = runCatching { String(data, javaCharset) }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: continue
            return text
        }
        return null
    }

    private fun resolveCharset(mibEnum: Int): java.nio.charset.Charset {
        val name = runCatching { CharacterSets.getMimeName(mibEnum) }.getOrNull()
        if (name.isNullOrEmpty() || name == "*") return Charsets.UTF_8
        return runCatching { charset(name) }.getOrDefault(Charsets.UTF_8)
    }

    /**
     * Writes the bytes to a stable file the AttachmentEntity will reference.
     *
     * **v1.3.10 (Q5)** : atomic `tmp + rename`. If the process is killed mid-write (OOM, MIUI
     * aggressive background kill), a half-written `in-*.ext` would otherwise be inserted into
     * Room — the user tapping the message would crash the image viewer on the truncated file.
     * Writing to `*.tmp` and only renaming on completion guarantees the consumer sees either
     * the full file or nothing.
     *
     * **v1.14.7** : storage moved from `cacheDir/mms_incoming/` to `filesDir/mms_attachments/`.
     * `cacheDir` est volatile — Android peut le purger en pression mémoire/stockage et "Effacer
     * le cache" via Réglages → Apps le vide aussi → les fichiers audio MMS reçus disparaissaient
     * sans bruit alors que les `AttachmentEntity.localUri` Room pointaient toujours vers ces
     * chemins. `filesDir/mms_attachments/` est persistent et n'est wipé que par PanicService ou
     * `clearData()`. MainApplication migre rétroactivement les chemins existants au cold-start.
     */
    private fun persistAttachment(appContext: Context, bytes: ByteArray, mime: String): File? {
        return try {
            val dir = File(appContext.filesDir, ATTACHMENTS_DIR).apply { mkdirs() }
            val ext = mimeExtension(mime)
            val name = "in-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.$ext"
            val finalFile = File(dir, name)
            val tmp = File(dir, "$name.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(finalFile)) {
                tmp.delete()
                Timber.w("persistAttachment rename failed for %s", finalFile.name)
                return null
            }
            finalFile
        } catch (t: Throwable) {
            Timber.w(t, "persistAttachment failed mime=%s", mime)
            null
        }
    }

    private fun mimeExtension(mime: String): String = when (mime.lowercase()) {
        "audio/mp4", "audio/aac", "audio/mp4a-latm" -> "m4a"
        "audio/amr", "audio/3gpp" -> "amr"
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/ogg", "audio/opus" -> "ogg"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "video/mp4" -> "mp4"
        "video/3gpp" -> "3gp"
        "video/webm" -> "webm"
        "application/pdf" -> "pdf"
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
        "application/msword" -> "doc"
        "application/zip" -> "zip"
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

    /**
     * Fallback label for the conversation list + notification when neither a `text/plain`
     * caption nor an MMS Subject is present. Picked to mirror Apple Messages / Google
     * Messages conventions (image/voice/video emoji + paperclip for generic files).
     */
    private fun defaultPreviewLabel(mime: String?): String {
        if (mime == null) return "[MMS]"
        val m = mime.lowercase()
        return when {
            m.startsWith("audio/") -> "🎤"
            m.startsWith("image/") -> "🖼️"
            m.startsWith("video/") -> "🎞️"
            else -> "📎"
        }
    }

    private companion object {
        /**
         * Legacy cacheDir subdirectory for incoming MMS attachments (v1.3.10 → v1.14.6).
         * Conservé comme constante pour la migration MainApplication qui rapatrie les fichiers
         * existants vers [ATTACHMENTS_DIR].
         */
        const val INCOMING_DIR: String = "mms_incoming"
        /** v1.14.7 — nouvelle racine persistante (filesDir) pour les attachments MMS reçus. */
        const val ATTACHMENTS_DIR: String = "mms_attachments"
        /** Audit M-10: TTL for the in-memory dedup set. 5 min covers real-world carrier replays. */
        const val DEDUP_TTL_MS: Long = 5 * 60 * 1_000L
        /**
         * v1.3.10 (SEC-03/P4) — hard ceiling on the dedup set. A storm of distinct fresh
         * transaction-ids (carrier hiccup or hypothetical replay flood from an internal
         * component) would otherwise let the map grow unbounded for the full TTL window.
         * 256 entries × ~40 B ≈ 10 KiB worst case — still well under any sane budget for
         * 5 minutes of MMS bursts, and gives the LinkedHashMap-LRU eviction priority over
         * the time-based prune when both kick in.
         */
        const val DEDUP_MAX_ENTRIES: Int = 256

        /**
         * Process-wide cache of recently-mirrored MMS transaction IDs. Bounded by
         * [DEDUP_TTL_MS] (time) AND [DEDUP_MAX_ENTRIES] (count). The [LinkedHashMap] preserves
         * insertion order so [removeEldestEntry] gives us a free LRU eviction; the existing
         * `removeAll { now - it.value > DEDUP_TTL_MS }` sweep still removes time-expired entries
         * on every new insert.
         */
        @JvmStatic
        private val processedTransactions = object : LinkedHashMap<String, Long>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Long>): Boolean =
                size > DEDUP_MAX_ENTRIES
        }
    }
}
