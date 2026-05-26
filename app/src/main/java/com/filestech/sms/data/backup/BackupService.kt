package com.filestech.sms.data.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.filestech.sms.core.crypto.AeadCipher
import com.filestech.sms.core.crypto.PasswordKdf
import com.filestech.sms.core.crypto.wipe
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.core.result.runCatchingOutcome
import com.filestech.sms.data.local.datastore.BackupFormat
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `.smsbk` format (v1):
 *
 *   "SMBK"(4) || version(1) || salt(16) || iter(4 BE) || aeadBlob
 *
 * `aeadBlob` = `AeadCipher.encryptRaw(rawKey, json, aad = MAGIC || version || salt || iter)`
 *  - `rawKey` = PBKDF2-HMAC-SHA512(password, salt, iter, 32 bytes)
 *  - KDF parameters are bound to the ciphertext via AAD: flipping `salt`/`iter` makes
 *    decryption fail-closed (fixes audit F26).
 *
 * XML SMS-Backup-Restore compat: unencrypted by design (user opt-in).
 */
@Singleton
class BackupService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val settings: SettingsRepository,
    private val kdf: PasswordKdf,
    private val aead: AeadCipher,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    @Serializable
    data class BackupHeader(val createdAt: Long, val app: String, val version: Int)

    @Serializable
    data class BackupPayload(
        val header: BackupHeader,
        val conversations: List<ConversationEntity>,
        val messages: List<MessageEntity>,
    )

    /**
     * Strict JSON parser: unknown keys are rejected to prevent forged-field injection (audit F9).
     */
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    /**
     * Unattended / scheduled backup. **Fixes F4 + A6**: refuses to produce a plaintext `.smsbk`.
     * The user must either explicitly opt-in to XML compat (documented unencrypted) OR enter a
     * passphrase from the UI for a `.smsbk` backup.
     */
    suspend fun runScheduledBackup(): Outcome<Uri> = withContext(io) {
        val s = settings.flow.first()
        val destString = s.backup.destinationUri ?: return@withContext Outcome.Failure(
            AppError.Validation("no destination uri configured"),
        )
        val destFolder = DocumentFile.fromTreeUri(context, Uri.parse(destString))
            ?: return@withContext Outcome.Failure(AppError.Storage())
        val fileName = "smstech_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}." +
            if (s.backup.format == BackupFormat.SMSBK) "smsbk" else "xml"
        val mime = if (s.backup.format == BackupFormat.SMSBK) "application/octet-stream" else "application/xml"
        val target = destFolder.createFile(mime, fileName)
            ?: return@withContext Outcome.Failure(AppError.Storage())
        when (s.backup.format) {
            BackupFormat.SMSBK -> Outcome.Failure(
                AppError.Validation("scheduled .smsbk requires a passphrase set via the UI"),
            )
            BackupFormat.XML_COMPAT -> writeXmlCompat(target.uri)
        }
    }

    /**
     * Writes an encrypted `.smsbk` to [uri]. The [password] CharArray is wiped on return.
     *
     * **Encryption is mandatory** (fixes F4). Pass an empty CharArray to get a `Validation`
     * failure — the call site is the only place where an explicit user passphrase enters the
     * pipeline, so we keep the contract strict.
     */
    suspend fun writeSmsbk(uri: Uri, password: CharArray): Outcome<Uri> = withContext(io) {
        runCatchingOutcome(
            block = {
                require(password.isNotEmpty()) { "password is required" }
                val payload = buildPayload()
                val plainBytes = json.encodeToString(BackupPayload.serializer(), payload)
                    .toByteArray(Charsets.UTF_8)
                val out = ByteArrayOutputStream()
                val magic = MAGIC.toByteArray(Charsets.US_ASCII)
                out.write(magic)
                out.write(byteArrayOf(VERSION.toByte()))
                val salt = kdf.newSalt()
                val iter = kdf.calibrate()
                val iterBytes = intToBytesBE(iter)
                out.write(salt)
                out.write(iterBytes)
                val aad = ByteArrayOutputStream(magic.size + 1 + salt.size + iterBytes.size).apply {
                    write(magic)
                    write(byteArrayOf(VERSION.toByte()))
                    write(salt)
                    write(iterBytes)
                }.toByteArray()
                try {
                    val rawKey = kdf.derive(password, salt, iter)
                    try {
                        when (val blob = aead.encryptRaw(rawKey, plainBytes, aad = aad)) {
                            is Outcome.Success -> out.write(blob.value)
                            is Outcome.Failure -> error("encrypt failed: ${blob.error}")
                        }
                    } finally {
                        rawKey.wipe()
                    }
                } finally {
                    password.wipe()
                }
                // v1.6.1 (audit QUAL-04) — diagnostic explicite si openOutputStream
                // retourne null (URI révoqué, disque plein, provider crashé). Avant le
                // `!!` produisait un NPE générique enveloppé dans AppError.Storage sans
                // message métier — debugging à l'aveugle.
                val os = context.contentResolver.openOutputStream(uri, "w")
                    ?: error("openOutputStream returned null for backup URI")
                os.use {
                    it.write(out.toByteArray())
                    it.flush()
                }
                uri
            },
            errorMapper = { AppError.Storage(it) },
        )
    }

    private suspend fun writeXmlCompat(uri: Uri): Outcome<Uri> = runCatchingOutcome(
        block = {
            val (_, msgs) = listSync()
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
            sb.append("<smses count=\"").append(msgs.size).append("\">\n")
            for (m in msgs) {
                sb.append("  <sms protocol=\"0\"")
                sb.append(" address=\"").append(xmlEscape(m.address)).append('"')
                sb.append(" date=\"").append(m.date).append('"')
                sb.append(" type=\"").append(if (m.direction == 1) 2 else 1).append('"')
                sb.append(" body=\"").append(xmlEscape(m.body)).append('"')
                sb.append(" read=\"").append(if (m.read) 1 else 0).append("\" />\n")
            }
            sb.append("</smses>\n")
            // v1.6.1 (audit QUAL-04) — idem, diagnostic explicite.
            val os = context.contentResolver.openOutputStream(uri, "w")
                ?: error("openOutputStream returned null for XML backup URI")
            os.use {
                it.write(sb.toString().toByteArray(Charsets.UTF_8))
            }
            uri
        },
        errorMapper = { AppError.Storage(it) },
    )

    private suspend fun buildPayload(): BackupPayload {
        val (convs, msgs) = listSync()
        return BackupPayload(
            header = BackupHeader(System.currentTimeMillis(), APP_TAG, VERSION),
            conversations = convs,
            messages = msgs,
        )
    }

    /**
     * Single-shot, transactional read of all conversations + all messages.
     * Fixes audit Q6 (was an N+1 with `Flow.first()` inside `flatMap`).
     */
    private suspend fun listSync(): Pair<List<ConversationEntity>, List<MessageEntity>> {
        val convs = conversationDao.listAllIncludingArchived()
        val msgs = messageDao.listAll()
        return convs to msgs
    }

    private fun intToBytesBE(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    /**
     * XML 1.0 attribute-safe escape (fixes F25/Q7). Escapes the five mandatory entities and drops
     * C0 control chars (U+0000..U+001F) except TAB / LF / CR which XML 1.0 permits. Drops DEL.
     */
    private fun xmlEscape(s: String): String = buildString(s.length + 8) {
        for (c in s) {
            val code = c.code
            when {
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '&' -> append("&amp;")
                c == '"' -> append("&quot;")
                c == '\'' -> append("&apos;")
                code in 0x00..0x1F && c != '\t' && c != '\n' && c != '\r' -> Unit
                code == 0x7F -> Unit
                else -> append(c)
            }
        }
    }

    // ============================================================================
    // v1.15.2 — Restore .smsbk : déchiffrement + import Room atomique.
    // ============================================================================

    /**
     * Résultat d'un restore — compte des objets importés vs réutilisés/skippés. Permet à l'UI
     * d'afficher un récap précis ("X conversations dont Y nouvelles, Z messages importés sur
     * N total dans la sauvegarde, M ignorés car déjà présents").
     */
    data class RestoreResult(
        val conversationsReused: Int,
        val conversationsCreated: Int,
        val messagesImported: Int,
        val messagesSkipped: Int,
    ) {
        val totalConversationsInBackup: Int get() = conversationsReused + conversationsCreated
        val totalMessagesInBackup: Int get() = messagesImported + messagesSkipped
    }

    /**
     * Lit, déchiffre et importe un fichier `.smsbk` v1.
     *
     * **Format attendu** (identique à [writeSmsbk]) :
     *   `"SMBK"(4) || version(1) || salt(16) || iter(4 BE) || aeadBlob`
     * Le `aeadBlob` est lié à `magic||version||salt||iter` via AAD, donc toute manipulation
     * du préfixe fait échouer le déchiffrement (fail-closed contre attaques par confusion).
     *
     * **Conflict resolution** :
     *  - Conversations : recherche par `addressesCsv` canonique. Si trouvée → réutilise l'id.
     *    Sinon → INSERT new avec id=0 (auto-generate Room).
     *  - Messages : INSERT avec `OnConflictStrategy.IGNORE`. Les rows existantes (mêmes
     *    `telephony_uri` unique index) sont silencieusement skippées — pas de duplication.
     *  - **Transaction atomique** ([AppDatabase.withTransaction]) : un crash / kill mi-import
     *    n'écrit rien (rollback complet). Vigilance MAX : pas d'état intermédiaire en Room.
     *
     * **Sécurité** :
     *  - Cap taille fichier (50 MB) pour éviter OOM sur un fichier hostile.
     *  - JSON parser strict (`ignoreUnknownKeys = false`) pour rejeter les payloads forgés.
     *  - `password` + `rawKey` wipés après usage.
     *  - `AppError.Validation` typé pour : magic invalide / version inconnue / mot de passe faux.
     *
     * Le [password] CharArray est consommé (wipé) sur retour, succès ou échec.
     */
    suspend fun readSmsbk(uri: Uri, password: CharArray): Outcome<RestoreResult> = withContext(io) {
        runCatchingOutcome(
            block = {
                require(password.isNotEmpty()) { "password is required" }
                val bytes = readBackupFile(uri)
                val header = parseHeader(bytes)
                val payload = decryptPayload(bytes, header, password)
                importPayload(payload)
            },
            errorMapper = { throwable ->
                // On préserve la nature de l'erreur côté UI : Validation (mauvais format /
                // mot de passe) vs Storage (problème de lecture) — pour afficher un message
                // ciblé. `runCatchingOutcome` wrap les exceptions ; on regarde le message.
                val msg = throwable.message.orEmpty()
                when {
                    msg.contains("invalid magic") ||
                        msg.contains("unsupported version") ||
                        msg.contains("decrypt failed") ||
                        msg.contains("invalid JSON") -> AppError.Validation(msg)
                    else -> AppError.Storage(throwable)
                }
            },
        ).also {
            // password wipé dans tous les chemins (succès comme échec) — `also` court-circuite
            // pas le retour de l'outcome.
            password.wipe()
        }
    }

    /**
     * Lecture en streaming chunked avec cap STRICT — audit SECU-H1 v1.15.2.
     *
     * Avant : `stream.readBytes()` allouait la totalité avant le check de cap → un fichier
     * hostile de 49,9 MB déclenchait 50 MB d'allocation heap AVANT le test, combiné au JSON
     * déchiffré (copie #2) puis la liste désérialisée (copie #3) → OOM probable sur S9
     * (~2 GB heap appli). Maintenant : lecture par buffers de 8 KB avec compteur cumulé,
     * abort dès dépassement de [MAX_RESTORE_BYTES] sans avoir alloué le reste. Cap dur.
     */
    private fun readBackupFile(uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("openInputStream returned null for restore URI")
        return input.use { stream ->
            val buf = ByteArray(READ_CHUNK_BYTES)
            // Pré-dimensionne à la borne basse pour limiter les ré-allocations sur backup
            // légitime ; le ByteArrayOutputStream croît en x2 au-delà.
            val out = ByteArrayOutputStream(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buf, 0, buf.size)
                if (read < 0) break
                total += read
                if (total > MAX_RESTORE_BYTES) {
                    // Le buf en cours n'est pas accumulé — on coupe net.
                    error("backup file too large (>$MAX_RESTORE_BYTES bytes)")
                }
                out.write(buf, 0, read)
            }
            out.toByteArray()
        }
    }

    /** Header parsé après validation magic + version + extraction salt + iter. */
    private data class ParsedHeader(val salt: ByteArray, val iter: Int, val aad: ByteArray, val aeadOffset: Int)

    private fun parseHeader(bytes: ByteArray): ParsedHeader {
        val minLen = MAGIC.length + 1 + SALT_LEN + ITER_LEN
        if (bytes.size < minLen) error("invalid backup: too short for header")
        val magicBytes = MAGIC.toByteArray(Charsets.US_ASCII)
        for (i in magicBytes.indices) {
            if (bytes[i] != magicBytes[i]) error("invalid magic — not a SMS Tech backup")
        }
        val version = bytes[MAGIC.length].toInt() and 0xFF
        if (version != VERSION) error("unsupported version $version (expected $VERSION)")
        val salt = bytes.copyOfRange(MAGIC.length + 1, MAGIC.length + 1 + SALT_LEN)
        val iterOffset = MAGIC.length + 1 + SALT_LEN
        val iter = bytesBEToInt(bytes, iterOffset)
        if (iter < KDF_ITER_MIN || iter > KDF_ITER_MAX) {
            error("invalid KDF iteration count $iter (out of $KDF_ITER_MIN..$KDF_ITER_MAX)")
        }
        val aeadOffset = iterOffset + ITER_LEN
        val aad = bytes.copyOfRange(0, aeadOffset)
        return ParsedHeader(salt = salt, iter = iter, aad = aad, aeadOffset = aeadOffset)
    }

    private fun decryptPayload(bytes: ByteArray, header: ParsedHeader, password: CharArray): BackupPayload {
        val aeadBlob = bytes.copyOfRange(header.aeadOffset, bytes.size)
        val rawKey = kdf.derive(password, header.salt, header.iter)
        val plain = try {
            when (val out = aead.decryptRaw(rawKey, aeadBlob, aad = header.aad)) {
                is Outcome.Success -> out.value
                is Outcome.Failure -> error("decrypt failed — wrong passphrase or corrupted file")
            }
        } finally {
            rawKey.wipe()
        }
        val payload = try {
            json.decodeFromString(BackupPayload.serializer(), plain.toString(Charsets.UTF_8))
        } catch (t: Throwable) {
            // Audit SECU-L2 v1.15.2 — `Timber.w` sans throwable pour ne pas leaker un fragment
            // du payload via le message d'exception en debug build.
            Timber.w("BackupService.readSmsbk: invalid JSON payload")
            error("invalid JSON payload")
        } finally {
            // Wipe plaintext bytes ASAP — contient potentiellement des SMS sensibles.
            // Note SECU-L1 : la `String` UTF-8 dérivée reste en heap (immuable JVM, non
            // wipeable) — limitation acceptée dans le threat model in-process documenté.
            plain.fill(0)
        }
        // Audit SECU-M1 v1.15.2 — Cap dur sur la volumétrie du payload pour bloquer un
        // fichier forgé contenant 10⁷ MessageEntity vides (DoS via boucle d'import qui gèle
        // l'app plusieurs minutes). Caps généreux pour un usage légitime (10⁴ conv ≈ 50 ans
        // d'utilisation intensive, 10⁶ msgs ≈ 100 msgs/jour pendant 27 ans).
        if (payload.conversations.size > MAX_RESTORE_CONVERSATIONS) {
            error("payload too large: ${payload.conversations.size} conversations")
        }
        if (payload.messages.size > MAX_RESTORE_MESSAGES) {
            error("payload too large: ${payload.messages.size} messages")
        }
        return payload
    }

    /**
     * Import atomique du payload en Room. Wrap dans [AppDatabase.withTransaction] — soit tout
     * passe, soit rollback complet (vigilance MAX : pas d'état intermédiaire). Réutilise les
     * conversations existantes par `addressesCsv` canonique ; insère les messages via
     * [MessageDao.insert] (OnConflictStrategy.IGNORE skip les dupes via index unique
     * `telephony_uri`).
     */
    private suspend fun importPayload(payload: BackupPayload): RestoreResult {
        return database.withTransaction {
            var reused = 0
            var created = 0
            // Mapping ancien id Room du backup → nouvel id Room dans la DB cible.
            val convIdMap = HashMap<Long, Long>(payload.conversations.size)
            // Audit SECU-M3 v1.15.2 — Placeholders négatifs UNIQUES par session de restore
            // pour le `thread_id` des conv créées. Pourquoi pas 0L : la table conversations
            // a un UNIQUE INDEX sur thread_id ; insérer N convs avec thread_id=0L déclenche
            // une UNIQUE constraint violation → OnConflictStrategy.REPLACE supprime
            // silencieusement les précédentes (seule la dernière survit). Les vrais
            // thread_id AOSP sont positifs (≥ 1) ; les négatifs sont garantis sans collision.
            // Le sync `TelephonySyncWorker` réassignera des thread_ids positifs lorsqu'il
            // ré-importera depuis le content provider système.
            val threadIdBase = -(System.currentTimeMillis())
            for ((convIndex, backupConv) in payload.conversations.withIndex()) {
                val existing = conversationDao.findByAddressesCsv(backupConv.addressesCsv)
                if (existing != null) {
                    convIdMap[backupConv.id] = existing.id
                    reused++
                } else {
                    val placeholderThreadId = threadIdBase - convIndex
                    val newId = conversationDao.upsert(
                        backupConv.copy(id = 0L, threadId = placeholderThreadId),
                    )
                    convIdMap[backupConv.id] = newId
                    created++
                }
            }
            var imported = 0
            var skipped = 0
            // Audit SECU-M4 v1.15.2 — Remapping en 2 passes pour préserver les `replyToMessageId`
            // (citations contextuelles). PASSE 1 : insert tous les messages, build map
            // <backupMsgId → newMsgId>. PASSE 2 : UPDATE replyToMessageId pour les messages
            // qui en avaient un dans le backup. Sans ce remapping, toutes les citations
            // restaurées pointaient vers des ids morts → UI affichait "Message supprimé"
            // pour chaque réponse, perte d'information utilisateur.
            val msgIdMap = HashMap<Long, Long>(payload.messages.size)
            for (backupMsg in payload.messages) {
                val newConvId = convIdMap[backupMsg.conversationId]
                if (newConvId == null) {
                    // Message orphelin (conversation absente du backup malgré référence).
                    // Skip défensif — ne pas créer une conv fantôme à partir d'un message.
                    skipped++
                    continue
                }
                // Reset à id=0 (auto), conversationId remappé, replyToMessageId temporairement
                // null (passe 2 le remettra).
                val toInsert = backupMsg.copy(id = 0L, conversationId = newConvId, replyToMessageId = null)
                val rowId = messageDao.insert(toInsert)
                if (rowId == -1L) {
                    skipped++
                } else {
                    imported++
                    msgIdMap[backupMsg.id] = rowId
                }
            }
            // Passe 2 — réécrit replyToMessageId pour les messages qui avaient une citation
            // dans le backup et dont la cible a été elle-même importée (pas une cible orpheline).
            for (backupMsg in payload.messages) {
                val replyTarget = backupMsg.replyToMessageId ?: continue
                val newId = msgIdMap[backupMsg.id] ?: continue
                val newReplyTarget = msgIdMap[replyTarget] ?: continue
                messageDao.setReplyTarget(newId, newReplyTarget)
            }
            RestoreResult(
                conversationsReused = reused,
                conversationsCreated = created,
                messagesImported = imported,
                messagesSkipped = skipped,
            )
        }
    }

    /** Big-endian Int read at [offset] (4 bytes). Inverse de [intToBytesBE]. */
    private fun bytesBEToInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    companion object {
        const val MAGIC = "SMBK"
        const val VERSION = 1
        private const val APP_TAG = "SMS Tech"
        // v1.15.2 — Cap de sécurité sur la taille d'un .smsbk à restaurer. 50 MB couvre des
        // archives très volumineuses (centaines de milliers de SMS) tout en bloquant un
        // fichier hostile qui tenterait un OOM. Lecture en streaming chunked (SECU-H1).
        private const val MAX_RESTORE_BYTES = 50 * 1024 * 1024
        private const val READ_CHUNK_BYTES = 8 * 1024
        private const val SALT_LEN = 16
        private const val ITER_LEN = 4
        // Bornes raisonnables pour le iteration count PBKDF2 ; protège contre un header
        // forgé qui demanderait des millions d'itérations pour DoS du device au déchiffrement.
        private const val KDF_ITER_MIN = 10_000
        private const val KDF_ITER_MAX = 2_000_000
        // Audit SECU-M1 v1.15.2 — Caps dur sur le nombre d'objets désérialisés du JSON.
        // Un fichier de 50 MB compressé peut contenir des dizaines de millions d'entités si
        // chaque entité est minimaliste — DoS de boucle d'import. Caps généreux :
        //  - 10 000 conv = ~50 ans d'utilisation intensive sur un single SIM
        //  - 1 000 000 msgs = ~100 msgs/jour pendant 27 ans
        private const val MAX_RESTORE_CONVERSATIONS = 10_000
        private const val MAX_RESTORE_MESSAGES = 1_000_000
    }
}
