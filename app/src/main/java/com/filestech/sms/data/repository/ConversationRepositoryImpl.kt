package com.filestech.sms.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.filestech.sms.core.ext.phoneSuffix8
import com.filestech.sms.core.ext.stripInvisibleChars
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.model.MessageWindow
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.PhoneAddress.Companion.toCsv
import com.filestech.sms.domain.model.toDomain
import com.filestech.sms.domain.purge.purgeCutoffMs
import com.filestech.sms.domain.repository.BlockedNumberRepository
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.repository.SetReactionResult
import com.filestech.sms.security.AppLockManager
import com.filestech.sms.system.notifications.IncomingMessageNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
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
    private val notifier: IncomingMessageNotifier,
    // v1.12.0 — résolution du displayName à la création d'une conv via
    // ComposeScreen. Sans ça, `findOrCreate` créait toujours
    // `displayName = null`, ce qui faisait afficher le numéro brut dans le
    // TopAppBar ThreadScreen au lieu du nom du contact pourtant choisi.
    private val contactRepo: com.filestech.sms.domain.repository.ContactRepository,
    // v1.8.0 (post-audit fix badges après désinstallation) — propage `READ=1`
    // côté système quand l'user ouvre une conversation. Sans ça, désinstaller
    // + réinstaller SMS Tech ramène les badges (le système avait toujours
    // `READ=0`, jamais aligné par SMS Tech).
    private val telephonyReader: com.filestech.sms.data.sms.TelephonyReader,
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
    override fun observeMessages(conversationId: Long): Flow<List<Message>> {
        // v1.6.1 (audit PERF-05) — `appLock.state` isolé du combine principal afin que
        // chaque déverrouillage biométrique / timeout lock NE déclenche PAS la
        // reconstruction complète des messages + fetch attachments.
        //
        // v1.14.9 (audit PERF-M6) — `conv.inVault` extrait via `distinctUntilChanged` AVANT
        // le combine messages. Avant : chaque write `draft` côté composer → `observeById`
        // ré-émettait → `combine` réévaluait → `attachmentDao.findForConversation` SQLCipher
        // re-tourné à chaque caractère tapé. Désormais : on n'observe que la slice utile
        // (inVault) ; le combine ne ré-évalue que si inVault OU messages changent réellement.
        val inVaultFlow = conversationDao.observeById(conversationId)
            .map { it?.inVault == true }
            .distinctUntilChanged()
        val baseFlow = combine(
            inVaultFlow,
            messageDao.observeForConversation(conversationId),
        ) { inVault, rows ->
            // Single bulk fetch — only hit Room if at least one row claims an attachment.
            // Avoids the SELECT round-trip on text-only conversations entirely.
            val needAttachments = rows.any { it.attachmentsCount > 0 }
            val attachmentsByMessage: Map<Long, List<Attachment>> =
                if (needAttachments) {
                    attachmentDao.findForConversation(conversationId)
                        .groupBy { it.messageId }
                        .mapValues { (_, list) -> list.map { it.toDomain() } }
                } else emptyMap()
            val messages = rows.map { entity ->
                entity.toDomain(attachmentsByMessage[entity.id].orEmpty())
            }
            inVault to messages
        }.flowOn(io)
        return combine(baseFlow, appLock.state) { (inVault, messages), lockState ->
            if (lockState is AppLockManager.LockState.PanicDecoy && inVault) {
                emptyList()
            } else {
                messages
            }
        }
    }

    /**
     * Bounded counterpart of [observeMessages] — see [MessageDao.observeWindowForConversation] for
     * why the thread UI must not read the whole history on every emission.
     *
     * Deliberately mirrors [observeMessages] rather than replacing it: the `inVault` isolation
     * (audit PERF-M6), the conditional attachment bulk-fetch (audit P-P0-1) and the `PanicDecoy`
     * guard are all load-bearing and stay identical. Only the source query is bounded, and the
     * window widens through [limit] via `flatMapLatest`.
     */
    override fun observeMessagesWindow(
        conversationId: Long,
        limit: Flow<Int>,
    ): Flow<MessageWindow> {
        val inVaultFlow = conversationDao.observeById(conversationId)
            .map { it?.inVault == true }
            .distinctUntilChanged()
        val rowsFlow = limit
            .distinctUntilChanged()
            .flatMapLatest { size -> messageDao.observeWindowForConversation(conversationId, size) }
        // Les statistiques sont volontairement HORS de ce combine : `messages` et l'agrégat sont
        // tous deux invalidés par la table `messages`, donc les réunir ici ferait tourner le
        // bulk-fetch des pièces jointes et le mapping des 200 lignes DEUX fois par message reçu —
        // annulant la moitié du gain visé. Ici on ne garde que le travail lourd.
        val mapped = combine(inVaultFlow, rowsFlow) { inVault, rows ->
            val needAttachments = rows.any { it.attachmentsCount > 0 }
            val attachmentsByMessage: Map<Long, List<Attachment>> =
                if (needAttachments) {
                    attachmentDao.findForConversation(conversationId)
                        .groupBy { it.messageId }
                        .mapValues { (_, list) -> list.map { it.toDomain() } }
                } else emptyMap()
            val messages = rows.map { entity ->
                entity.toDomain(attachmentsByMessage[entity.id].orEmpty())
            }
            inVault to messages
        }.flowOn(io)

        // Assemblage seulement : une ré-émission des stats ne coûte plus qu'une data class.
        return combine(
            mapped,
            messageDao.observeStatsForConversation(conversationId).distinctUntilChanged(),
            appLock.state,
        ) { (inVault, messages), stats, lockState ->
            if (lockState is AppLockManager.LockState.PanicDecoy && inVault) {
                MessageWindow()
            } else {
                MessageWindow(
                    messages = messages,
                    totalCount = stats.total,
                    hasMore = messages.size < stats.total,
                    firstMessageAt = stats.firstAt,
                    lastMessageAt = stats.lastAt,
                )
            }
        }
    }

    override fun observeUnreadConversationCount(): Flow<Int> =
        conversationDao.observeUnreadConversationCount().flowOn(io)

    /**
     * Audit A10: wraps the read-modify-write in a single Room transaction so two concurrent
     * `findOrCreate` calls for the same canonical CSV cannot create two separate rows.
     */
    override suspend fun findOrCreate(addresses: List<PhoneAddress>): Outcome<Conversation> = withContext(io) {
        if (addresses.isEmpty()) return@withContext Outcome.Failure(AppError.Validation("empty addresses"))
        val csv = canonicalCsv(addresses)
        // v1.12.0 — resolve le displayName AVANT la transaction Room pour le
        // cas single-recipient (parcours classique ComposeScreen). En groupe
        // (≥ 2 recipients), on garde null : le rendu UI joint les numéros / les
        // noms côté présentation (cf. `ConversationRow`), poser un seul
        // displayName tronqué serait trompeur.
        //
        // Lookup hors-transaction pour ne pas tenir le verrou Room pendant
        // l'appel `ContentResolver` (qui peut bloquer ~5-30 ms si la base
        // contacts est froide). En cas d'échec (READ_CONTACTS révoqué,
        // contact inexistant), `displayName` reste null et l'UI tombera
        // gracieusement sur `addresses.joinToString { it.raw }` — comportement
        // pré-v1.12.0 préservé.
        //
        // v1.12.0 audit B2 — sanitization Bidi/invisible via
        // `stripInvisibleChars()` (helper existant) : neutralise les
        // caractères RLO/LRO/ZWJ/BOM qui pourraient être présents dans un
        // nom de contact vCard importé. Sans ça, un nom avec U+202E pourrait
        // inverser le rendu du TopAppBar ThreadScreen.
        val resolvedDisplayName: String? = if (addresses.size == 1) {
            runCatching { contactRepo.lookupByPhone(addresses[0].raw)?.displayName }
                .getOrNull()
                ?.stripInvisibleChars()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        } else null

        val id = database.withTransaction {
            val existing = conversationDao.findByAddressesCsv(csv)
            if (existing != null) {
                // v1.12.0 audit B1 — back-fill displayName si la conv existe
                // déjà mais avec displayName null/blanc ET qu'on vient d'en
                // résoudre un. Sans ça, les conv créées avant v1.12.0 (où le
                // resolve n'était pas fait) gardent leur numéro brut même
                // après ré-ouverture via ComposeScreen.
                if (existing.displayName.isNullOrBlank() && !resolvedDisplayName.isNullOrBlank()) {
                    conversationDao.setDisplayName(existing.id, resolvedDisplayName)
                }
                existing.id
            } else {
                // Fix doublon "Nouvelle conversation" — AVANT de créer une nouvelle
                // conversation, on rejoue le fallback suffix-8 déjà éprouvé côté
                // [ConversationMirror.ensureConversation]. Sans lui, composer un message
                // vers un contact dont le numéro est stocké au format national
                // (`06 12 34 56 78`) alors qu'une conversation existe déjà au format
                // international (`+33612345678`, tel que reçu du système) ne matchait PAS
                // l'égalité stricte du CSV (`findByAddressesCsv` compare la forme brute) →
                // une 2ᵉ conversation était créée pour le même correspondant.
                // Restreint aux 1-to-1, mêmes garde-fous que le mirror : deux participants
                // partiels d'un groupe ne doivent jamais être rapprochés par suffixe.
                val suffixMatch = if (addresses.size == 1) {
                    matchOneToOneBySuffix8(
                        conversationDao.snapshotOneToOneConversations(),
                        addresses.first(),
                    )
                } else null

                if (suffixMatch != null) {
                    if (suffixMatch.displayName.isNullOrBlank() && !resolvedDisplayName.isNullOrBlank()) {
                        conversationDao.setDisplayName(suffixMatch.id, resolvedDisplayName)
                    }
                    suffixMatch.id
                } else {
                    conversationDao.upsert(
                        ConversationEntity(
                            threadId = 0L,
                            addressesCsv = csv,
                            displayName = resolvedDisplayName,
                            lastMessageAt = System.currentTimeMillis(),
                            lastMessagePreview = null,
                            unreadCount = 0,
                        ),
                    )
                }
            }
        }
        Outcome.Success(requireNotNull(conversationDao.findById(id)?.toDomain()))
    }

    /** Canonical CSV: addresses sorted by normalized value so reordering does not duplicate threads. */
    private fun canonicalCsv(addresses: List<PhoneAddress>): String =
        addresses.sortedBy { it.normalized }.toCsv()

    companion object {
        /**
         * Rapprochement d'une conversation 1-to-1 par ses **8 derniers chiffres**. Extrait ici
         * en fonction pure (aucune dépendance Room) pour être testable en JVM et appelé depuis
         * [findOrCreate] : quand l'égalité stricte du CSV échoue (`06 12 34 56 78` composé vs
         * `+33612345678` stocké), ce fallback réunit les deux formes du même numéro et évite un
         * doublon de conversation. Même stratégie que [ConversationMirror.ensureConversation]
         * côté réception.
         *
         * Ne matche que si le suffixe fait exactement 8 chiffres : les numéros courts (services,
         * 3xxx) sont trop peu discriminants pour être rapprochés sans risque de faux positif.
         * L'appelant restreint déjà aux 1-to-1 ; on ne rapproche jamais deux groupes par suffixe.
         */
        internal fun matchOneToOneBySuffix8(
            oneToOne: List<ConversationEntity>,
            target: PhoneAddress,
        ): ConversationEntity? {
            val targetSuffix = target.raw.phoneSuffix8()
            if (targetSuffix.length != 8) return null
            return oneToOne.firstOrNull { conv ->
                PhoneAddress.list(conv.addressesCsv).firstOrNull()
                    ?.raw?.phoneSuffix8() == targetSuffix
            }
        }
    }

    override suspend fun setPinned(id: Long, pinned: Boolean) = withContext(io) { conversationDao.setPinned(id, pinned) }
    override suspend fun setArchived(id: Long, archived: Boolean) = withContext(io) { conversationDao.setArchived(id, archived) }
    override suspend fun setMuted(id: Long, muted: Boolean) = withContext(io) { conversationDao.setMuted(id, muted) }
    override suspend fun moveToVault(id: Long, inVault: Boolean) = withContext(io) { conversationDao.setInVault(id, inVault) }

    // v1.14.8 R8 — Bulk move atomique : wrap dans `database.withTransaction` pour qu'un
    // process-kill au milieu de la boucle laisse la base intacte (rollback) plutôt qu'un
    // état partiel non-récupérable. Retourne le count effectivement modifié.
    override suspend fun bulkMoveToVault(ids: List<Long>, inVault: Boolean): Int = withContext(io) {
        if (ids.isEmpty()) return@withContext 0
        database.withTransaction {
            var n = 0
            for (id in ids) {
                conversationDao.setInVault(id, inVault)
                n++
            }
            n
        }
    }
    override suspend fun setDraft(id: Long, draft: String?) = withContext(io) { conversationDao.setDraft(id, draft) }
    override suspend fun setAppearance(id: Long, bubbleColorArgb: Int?, avatarUri: String?) =
        withContext(io) {
            // v1.11.0 audit S5 — whitelist `content://` pour avatar_uri.
            // PickVisualMedia ne retourne que ce scheme par contrat Android,
            // mais on défend en profondeur : un bug futur ou un test qui
            // passerait `file://` ou `http://` serait neutralisé ici (Coil
            // chargerait sinon depuis fichier local ou réseau → fuite ou
            // path traversal). null reste valide (= reset).
            val safeAvatarUri = avatarUri?.takeIf { uri ->
                val scheme = android.net.Uri.parse(uri).scheme?.lowercase()
                scheme == "content"
            }
            conversationDao.setAppearance(id, bubbleColorArgb, safeAvatarUri)
        }
    override suspend fun markRead(id: Long) = withContext(io) {
        messageDao.markConversationRead(id)
        conversationDao.clearUnread(id)
        // v1.8.0 (post-audit fix badges après désinstallation) — propage
        // `READ=1` côté `content://sms` + `content://mms` pour les messages
        // incoming de cette conversation. Récupère le `threadId` système
        // depuis Room (ConversationEntity.threadId stocke le mapping AOSP).
        // Wrapped en runCatching pour ne pas bloquer le markRead Room si le
        // content provider est indisponible (panic mode, ROM custom).
        runCatching {
            val systemThreadId = conversationDao.findThreadIdById(id)
            if (systemThreadId != null && systemThreadId > 0L) {
                telephonyReader.markConversationReadInSystem(systemThreadId)
            }
        }
        // v1.3.3 bug #6 — efface aussi les notifications encore affichées dans la
        // barre système pour cette conversation. Sans ça, ouvrir une thread depuis l'app
        // (et non depuis la notif elle-même) laissait les notifs s'accumuler dans le
        // shade. `cancelAllForConversation` itère sur les notifs actives filtrées par
        // groupe `com.filestech.sms.conv.<id>`.
        notifier.cancelAllForConversation(id)
    }

    // v1.14.8 H4 — Implémentation centralisée de "tout marquer comme lu". Encapsule la séquence
    // Room (markAllIncomingAsRead + recomputeAllUnreadCounts) + propagation content://sms+mms
    // pour que le badge ne ré-apparaisse pas après désinstall/reinstall. Avant : la VM
    // orchestrait elle-même en injectant 2 DAOs + le TelephonyReader (layer violation).
    override suspend fun markAllRead() = withContext(io) {
        runCatching {
            messageDao.markAllIncomingAsRead()
            conversationDao.recomputeAllUnreadCounts()
            telephonyReader.markAllIncomingReadInSystem()
        }
        Unit
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

    override suspend fun setReaction(messageId: Long, emoji: String?): SetReactionResult =
        withContext(io) {
            // v1.3.0 audit Q1 — passe par le repo (cohérence pattern domain/data) plutôt
            // que de laisser le ViewModel toucher directement au DAO. Audit F4 — court-
            // circuit no-op si la valeur est déjà la même : évite le churn FTS4
            // (DELETE+INSERT trigger pour `body` et `address` à chaque tap, alors que ces
            // colonnes ne changent pas) et évite un round-trip Flow inutile. `findById` est
            // suspend + indexé sur PRIMARY KEY, négligeable.
            //
            // v1.3.1 — retourne un [SetReactionResult] explicite pour que le ViewModel puisse
            // décider d'envoyer un SMS au correspondant (cas [First] uniquement) sans avoir à
            // re-lire la DB. La transition est calculée AVANT l'update afin que l'ordre
            // d'exécution soit déterministe et qu'un retry suite à exception ne fasse pas
            // double-envoi côté caller.
            val current = messageDao.findById(messageId) ?: return@withContext SetReactionResult.Noop
            val previous = current.reactionEmoji
            if (previous == emoji) return@withContext SetReactionResult.Noop
            messageDao.setReaction(messageId, emoji)
            when {
                previous == null && emoji != null -> SetReactionResult.First(messageId, emoji)
                previous != null && emoji == null -> SetReactionResult.Removed
                previous != null && emoji != null -> SetReactionResult.Changed(previous, emoji)
                else -> SetReactionResult.Noop // unreachable (couvert par le check d'égalité)
            }
        }

    override suspend fun countMessagesToPurge(olderThanDays: Int): Int = withContext(io) {
        if (olderThanDays <= 0) return@withContext 0
        val cutoff = purgeCutoffMs(olderThanDays)
        messageDao.countOlderThan(cutoff)
    }

    override suspend fun purgeHistoryNow(olderThanDays: Int): Int = withContext(io) {
        if (olderThanDays <= 0) return@withContext 0
        val cutoff = purgeCutoffMs(olderThanDays)
        val purged = messageDao.purgeOlderThan(cutoff)
        if (purged > 0) {
            // v1.3.3 G1 audit fix — refresh preview/last_message_at après purge pour
            // éviter qu'une conv vidée garde l'ancien preview en clair (leak privacy).
            messageDao.refreshAllConversationPreviewsAfterPurge()
        }
        purged
    }

    // v1.6.1 (audit QUAL-01) — `purgeCutoffMs` et `SAFETY_NET_DAYS` centralisés dans
    // [com.filestech.sms.domain.purge.PurgePolicy]. Tous les call sites passent par là.

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

    override suspend fun findMessageById(id: Long): Message? = withContext(io) {
        // v1.3.1 — lookup PRIMARY KEY, pas de jointure attachments (le caller actuel —
        // SendReactionUseCase — n'en a pas besoin). Si un futur caller le demande, ajouter
        // un overload `findMessageByIdWithAttachments`. Volontairement minimal pour rester
        // O(1) sur la lecture la plus chaude (chaque tap réaction).
        //
        // v1.11.0 audit SEC-V1 — guard inVault : ce point d'entrée ne doit
        // pas exposer un message dont la conversation parente est dans le
        // coffre. Sans ce guard, un caller futur (deep link, intent,
        // reaction sur un id résolu via une autre voie) pourrait lire le
        // body d'un message vault. PanicDecoy a déjà sa propre garde via
        // `observeOne`/`observeMessages` — celui-ci est complémentaire pour
        // les chemins suspend non-flow.
        val entity = messageDao.findById(id) ?: return@withContext null
        val parent = conversationDao.findById(entity.conversationId)
        if (parent?.inVault == true) return@withContext null
        entity.toDomain()
    }

    // v1.6.1 (audit QUAL-18) — délégation à la fonction top-level pure
    // [escapeFtsQuery] (testable sans instance ConversationRepositoryImpl).
    private fun escapeFtsQuery(input: String): String =
        com.filestech.sms.data.repository.escapeFtsQuery(input)

    // v1.6.1 (audit QUAL-01) — SAFETY_NET_DAYS centralisé dans
    // [com.filestech.sms.domain.purge.PurgePolicy]. Plus de companion local nécessaire.
}
