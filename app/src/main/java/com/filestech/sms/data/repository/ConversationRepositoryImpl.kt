package com.filestech.sms.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.core.ext.phoneSuffix8
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.PhoneAddress.Companion.toCsv
import com.filestech.sms.domain.model.toDomain
import com.filestech.sms.domain.repository.BlockedNumberRepository
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.security.AppLockManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepositoryImpl @Inject constructor(
    private val database: AppDatabase,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val attachmentDao: AttachmentDao,
    private val appLock: AppLockManager,
    private val blockedRepo: BlockedNumberRepository,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ConversationRepository {

    /**
     * Main list stream. Filters out conversations whose **every** address is blocked.
     *
     * Matching uses the [com.filestech.sms.core.ext.phoneSuffix8] suffix (last 8 digits) rather
     * than full-string equality so a number blocked in international form (`+33612345678` via
     * Téléphone / Samsung Messages) still matches the national form stored in `content://sms`
     * (`0612345678`). Groups with one blocked + one allowed participant stay visible — the
     * receiver-side filter in `SmsDeliverReceiver` already drops the blocked party's messages.
     */
    override fun observeAll(includeArchived: Boolean): Flow<List<Conversation>> =
        combine(
            conversationDao.observe(includeArchived),
            blockedRepo.observe(),
        ) { rows, blocked ->
            val blockedSuffixes = blocked
                .map { it.normalizedNumber.phoneSuffix8() }
                .filter { it.isNotEmpty() }
                .toHashSet()
            rows.asSequence()
                .map { it.toDomain() }
                .filter { conv ->
                    if (conv.addresses.isEmpty()) return@filter true
                    conv.addresses.any { addr -> addr.normalized.phoneSuffix8() !in blockedSuffixes }
                }
                .toList()
        }.flowOn(io)

    /**
     * Vault listing — **gated against [AppLockManager.LockState.PanicDecoy]**.
     *
     * The panic-decoy session intentionally exposes the regular conversations list (so an
     * attacker reading over the user's shoulder sees a plausible, working app), but must never
     * leak the encrypted vault. Without this gate, simply tapping the vault icon would surface
     * every protected conversation in clear — the whole point of the panic code is to keep
     * that content out of reach until the real PIN is entered.
     *
     * `combine` re-evaluates whenever the lock state changes, so the moment the user transitions
     * out of decoy back to a real unlock (next session), the list re-populates without manual
     * refresh.
     */
    override fun observeVault(): Flow<List<Conversation>> =
        combine(appLock.state, conversationDao.observeVault()) { lockState, list ->
            if (lockState is AppLockManager.LockState.PanicDecoy) emptyList<Conversation>()
            else list.map { it.toDomain() }
        }.flowOn(io)

    /**
     * Single conversation row stream. **Hidden when the conversation is in the vault and the
     * session is a panic decoy** — same rationale as [observeVault]: deep-linking to a vault
     * thread (saved nav state, share-target shortcut, future widget) must not bypass the gate.
     */
    override fun observeOne(id: Long): Flow<Conversation?> =
        combine(appLock.state, conversationDao.observeById(id)) { lockState, entity ->
            when {
                entity == null -> null
                lockState is AppLockManager.LockState.PanicDecoy && entity.inVault -> null
                else -> entity.toDomain()
            }
        }.flowOn(io)

    /**
     * Messages stream for a given conversation. **Empty list when the host conversation is in
     * the vault and the session is a panic decoy.**
     *
     * Audit P-P0-1: attachments are now bulk-fetched once per emission via
     * [AttachmentDao.findForConversation] and grouped in memory, replacing the old N+1
     * pattern that ran one Room query per audio row. The grouping is done lazily — text-only
     * threads pay zero attachment cost because the bulk SELECT against `attachments` returns
     * an empty list (covered by the `message_id` index).
     */
    override fun observeMessages(conversationId: Long): Flow<List<Message>> =
        combine(
            appLock.state,
            conversationDao.observeById(conversationId),
            messageDao.observeForConversation(conversationId),
        ) { lockState, conv, rows ->
            if (lockState is AppLockManager.LockState.PanicDecoy && conv?.inVault == true) {
                emptyList<Message>()
            } else {
                // Single bulk fetch — only hit Room if at least one row claims an attachment.
                // Avoids the SELECT round-trip on text-only conversations entirely.
                val needAttachments = rows.any { it.attachmentsCount > 0 }
                val attachmentsByMessage: Map<Long, List<Attachment>> =
                    if (needAttachments) {
                        attachmentDao.findForConversation(conversationId)
                            .groupBy { it.messageId }
                            .mapValues { (_, list) -> list.map { it.toDomain() } }
                    } else emptyMap()
                rows.map { entity ->
                    entity.toDomain(attachmentsByMessage[entity.id].orEmpty())
                }
            }
        }.flowOn(io)

    override fun observeUnreadConversationCount(): Flow<Int> =
        conversationDao.observeUnreadConversationCount().flowOn(io)

    /**
     * Audit A10: wraps the read-modify-write in a single Room transaction so two concurrent
     * `findOrCreate` calls for the same canonical CSV cannot create two separate rows.
     */
    override suspend fun findOrCreate(addresses: List<PhoneAddress>): Outcome<Conversation> = withContext(io) {
        if (addresses.isEmpty()) return@withContext Outcome.Failure(AppError.Validation("empty addresses"))
        val csv = canonicalCsv(addresses)
        val id = database.withTransaction {
            val existing = conversationDao.findByAddressesCsv(csv)
            existing?.id ?: conversationDao.upsert(
                ConversationEntity(
                    threadId = 0L,
                    addressesCsv = csv,
                    displayName = null,
                    lastMessageAt = System.currentTimeMillis(),
                    lastMessagePreview = null,
                    unreadCount = 0,
                ),
            )
        }
        Outcome.Success(requireNotNull(conversationDao.findById(id)?.toDomain()))
    }

    /** Canonical CSV: addresses sorted by normalized value so reordering does not duplicate threads. */
    private fun canonicalCsv(addresses: List<PhoneAddress>): String =
        addresses.sortedBy { it.normalized }.toCsv()

    override suspend fun setPinned(id: Long, pinned: Boolean) = withContext(io) { conversationDao.setPinned(id, pinned) }
    override suspend fun setArchived(id: Long, archived: Boolean) = withContext(io) { conversationDao.setArchived(id, archived) }
    override suspend fun setMuted(id: Long, muted: Boolean) = withContext(io) { conversationDao.setMuted(id, muted) }
    override suspend fun moveToVault(id: Long, inVault: Boolean) = withContext(io) { conversationDao.setInVault(id, inVault) }
    override suspend fun setDraft(id: Long, draft: String?) = withContext(io) { conversationDao.setDraft(id, draft) }
    override suspend fun markRead(id: Long) = withContext(io) {
        messageDao.markConversationRead(id)
        conversationDao.clearUnread(id)
    }
    override suspend fun delete(id: Long) = withContext(io) {
        // Propagate to the system SMS/MMS content provider before dropping the local rows.
        // Otherwise a re-import (manual refresh, factory reset, panic + re-grant) would
        // resurrect every message and the conversation would reappear out of nowhere.
        runCatching {
            val msgs = messageDao.findByConversation(id)
            for (m in msgs) deleteFromTelephonyProvider(m.telephonyUri)
        }
        conversationDao.delete(id)
    }
    override suspend fun deleteMessage(messageId: Long) = withContext(io) {
        // Same rationale as [delete]: remove the row from the system content provider so the
        // next launch (or a future re-import) doesn't bring it back.
        val msg = messageDao.findById(messageId)
        deleteFromTelephonyProvider(msg?.telephonyUri)
        messageDao.delete(messageId)
    }

    /**
     * Deletes a single SMS / MMS row from the system content provider, identified by the URI we
     * captured at insert/import time. No-op when [telephonyUri] is null (e.g. drafts created
     * before the row was mirrored) or when the OS refuses the delete (SecurityException — we are
     * no longer the default SMS app). Failures are swallowed because the Room delete must still
     * succeed: the user expects the message to disappear from the app even if the system row
     * lingers and gets cleaned up the next time we are default.
     */
    private fun deleteFromTelephonyProvider(telephonyUri: String?) {
        if (telephonyUri.isNullOrBlank()) return
        runCatching {
            context.contentResolver.delete(Uri.parse(telephonyUri), null, null)
        }.onFailure { Timber.w(it, "Failed to delete %s from system provider", telephonyUri) }
    }
    override suspend fun search(query: String): List<Message> = withContext(io) {
        val safe = escapeFtsQuery(query)
        if (safe.isBlank()) emptyList() else messageDao.search(safe).map { it.toDomain() }
    }

    /**
     * Audit P11 + R3: SQLite FTS4 treats `"`, `*`, `^`, `:`, `-`, `(`, `)`, `+` as syntax. A query
     * containing any of these previously made `MATCH` raise `SQLiteException` at runtime. The
     * previous implementation wrapped each token in double quotes and suffixed `*` AFTER the
     * closing quote — which is invalid FTS4 syntax (`"foo"*` is not a prefix query).
     *
     * The new strategy:
     *  - drop control chars (C0, DEL, bidi/zero-width)
     *  - per token: strip every FTS reserved char so the token contains only safe word chars
     *  - per (non-blank) token: append `*` to enable prefix matching
     *  - join tokens with a single space (implicit AND in FTS)
     */
    private fun escapeFtsQuery(input: String): String {
        val cleaned = input
            .replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
            .replace(Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]"), "")
            .trim()
        if (cleaned.isEmpty()) return ""
        return cleaned
            .split(Regex("\\s+"))
            .asSequence()
            .map { it.replace(Regex("[\"*^():+\\-]"), "") }
            .filter { it.isNotBlank() }
            .joinToString(separator = " ") { "${it}*" }
    }
}
