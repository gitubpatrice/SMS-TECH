package com.filestech.sms.data.blocking

import android.os.Build
import com.filestech.sms.core.ext.phoneSuffix8
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.BlockedNumberRepository
import com.filestech.sms.domain.repository.ConversationRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mirrors the OS-wide [android.provider.BlockedNumberContract] blocklist into our Room cache
 * at boot. Without this, a user who had already blocked numbers via Téléphone / Samsung
 * Messages would have to re-block them inside SMS Tech, which is exactly the pain point the
 * v1.1.x roadmap calls out.
 *
 * Read access to the system table requires either:
 *  - the default-SMS-app role (granted in our case once the user accepts the prompt), OR
 *  - the `READ_BLOCKED_NUMBERS` permission (carrier-only, not available to user apps).
 *
 * If neither applies the read silently returns an empty list — that's the desired behaviour
 * before the user finishes onboarding: no crash, no banner, we'll re-try at the next boot.
 *
 * Mirroring is **one-way**: system → app. We never push back to the contract here, so this
 * importer cannot create a feedback loop with the existing [BlockedNumberRepositoryImpl.block]
 * code path (which DOES push to the system on user-initiated blocks).
 */
@Singleton
class BlockedNumbersImporter @Inject constructor(
    private val system: BlockedNumberSystem,
    private val repo: BlockedNumberRepository,
    private val conversationRepo: ConversationRepository,
    private val conversationDao: ConversationDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Recopie dans Room les numéros bloqués au niveau système qui ne le sont pas encore.
     *
     * v1.25.3 — **ne purge plus les conversations correspondantes.** Cette fonction est appelée
     * au démarrage de l'app ([com.filestech.sms.MainApplication]) et à chaque synchronisation
     * ([com.filestech.sms.data.sync.TelephonySyncManager]) : la purge qu'elle enchaînait
     * supprimait donc **automatiquement, en tâche de fond**, tout fil dont les participants
     * étaient bloqués — de Room *et* de `content://sms`, donc sans retour possible. Bloquer un
     * numéro revenait à effacer l'historique quelques secondes plus tard, sans que rien ne
     * l'annonce et sans qu'aucun déblocage puisse le rendre.
     *
     * [purgeMatchingConversations] reste disponible, mais **uniquement** sur action explicite de
     * l'utilisateur depuis les Réglages. Bloquer masque et signale ; effacer se demande.
     *
     * Idempotent : rejouer à chaque démarrage à froid est peu coûteux, et sans effet quand rien
     * n'a changé.
     */
    suspend fun importFromSystem() = withContext(io) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@withContext
        val systemNumbers = runCatching { system.listSystemBlocked() }.getOrDefault(emptyList())
        if (systemNumbers.isNotEmpty()) {
            var imported = 0
            for (raw in systemNumbers) {
                runCatching { repo.mirrorFromSystem(raw) }
                    .onSuccess { imported += 1 }
                    .onFailure { Timber.w(it, "Mirror failed for %s", raw) }
            }
            Timber.i("BlockedNumbersImporter: mirrored %d system entries", imported)
        }
    }

    /**
     * Walks the current conversation list (including archived) and deletes any whose **every**
     * participant matches a blocked-number suffix. Suffix-8 matching absorbs international /
     * national format mismatches (see [phoneSuffix8]). The deletion cascades to messages via
     * the Room FK and to the system provider via [ConversationRepository.delete].
     *
     * Returns the number of conversations actually purged so the caller (Settings action,
     * scheduled boot run) can surface a snackbar.
     */
    suspend fun purgeMatchingConversations(): Int = withContext(io) {
        val blockedSuffixes = repo.blockedNormalizedSnapshot()
            .asSequence()
            .map { it.phoneSuffix8() }
            .filter { it.isNotEmpty() }
            .toHashSet()
        Timber.i("Purge: %d distinct blocked suffixes", blockedSuffixes.size)
        if (blockedSuffixes.isEmpty()) return@withContext 0
        // Read directly from the DAO — not `ConversationRepository.observeAll()`. Le motif
        // d'origine (« observeAll filtre déjà les conversations bloquées ») n'est plus vrai
        // depuis la v1.25.3 : elles restent listées, marquées via `Conversation.blocked`. La
        // lecture directe reste néanmoins la bonne : elle donne l'instantané brut, sans
        // dépendre d'un flux d'affichage ni du marquage, dont la purge n'a que faire.
        val allEntities = runCatching { conversationDao.snapshotAllNonVault() }.getOrDefault(emptyList())
        Timber.i("Purge: %d conversations to evaluate (unfiltered snapshot)", allEntities.size)
        var purged = 0
        var partialMatch = 0
        for (entity in allEntities) {
            val addresses = PhoneAddress.list(entity.addressesCsv)
            if (addresses.isEmpty()) continue
            val addrSuffixes = addresses.map { it.normalized.phoneSuffix8() }
            val matchCount = addrSuffixes.count { it in blockedSuffixes }
            if (matchCount == 0) continue
            if (matchCount < addrSuffixes.size) {
                partialMatch += 1
                // v1.6.1 (audit SEC-05) — `addrs=%s` retiré : les suffixes téléphoniques
                // 8 chiffres sont des quasi-identifiants PII (RGPD). En release R8 strip
                // tout l'appel Timber via assumenosideeffects mais en debug logcat les
                // exposait à toute app détentrice de READ_LOGS.
                Timber.d("Purge: conv #%d partial-block (%d/%d) — keeping", entity.id, matchCount, addrSuffixes.size)
                continue
            }
            Timber.i("Purge: deleting conv #%d (all %d participants blocked)", entity.id, addrSuffixes.size)
            runCatching { conversationRepo.delete(entity.id) }
                .onSuccess { purged += 1 }
                .onFailure { Timber.w(it, "Purge of blocked conv #%d failed", entity.id) }
        }
        Timber.i("Purge: result purged=%d partialMatch=%d", purged, partialMatch)
        purged
    }
}
