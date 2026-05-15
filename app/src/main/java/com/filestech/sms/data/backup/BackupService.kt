package com.filestech.sms.data.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.filestech.sms.core.crypto.AeadCipher
import com.filestech.sms.core.crypto.PasswordKdf
import com.filestech.sms.core.crypto.wipe
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.core.result.runCatchingOutcome
import com.filestech.sms.data.local.datastore.BackupFormat
import com.filestech.sms.data.local.datastore.SettingsRepository
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
                context.contentResolver.openOutputStream(uri, "w")!!.use { os ->
                    os.write(out.toByteArray())
                    os.flush()
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
            context.contentResolver.openOutputStream(uri, "w")!!.use {
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

    companion object {
        const val MAGIC = "SMBK"
        const val VERSION = 1
        private const val APP_TAG = "SMS Tech"
    }
}
