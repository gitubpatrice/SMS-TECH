package com.filestech.sms.data.blocking

import android.os.Build
import com.filestech.sms.core.ext.blockKey
import com.filestech.sms.data.local.db.dao.BlockedNumberDao
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
    private val phoneIdentity: com.filestech.sms.data.sms.PhoneIdentity,
    private val conversationRepo: ConversationRepository,
    private val conversationDao: ConversationDao,
    // v1.25.4 — accès direct au DAO, comme [conversationDao] : `rekeyLegacyEntries` réécrit une
    // colonne de stockage, une opération de maintenance qui n'a pas à remonter dans le contrat
    // de domaine.
    private val blockedNumberDao: BlockedNumberDao,
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
        // v1.25.4 — AVANT le miroir, et avant même le garde de version : une entrée restée sur
        // l'ancienne clé ne bloque plus rien, donc plus tôt elle est convertie, mieux c'est.
        runCatching { rekeyLegacyEntries() }
            .onFailure { Timber.w(it, "rekeyLegacyEntries failed") }
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
     * v1.25.4 — convertit les entrées enregistrées sous l'ancienne clé vers
     * [com.filestech.sms.core.ext.blockKey]. Rend le nombre d'entrées converties.
     *
     * Jusqu'à la v1.25.3 la clé valait `normalizePhone(raw)` : « SFR » s'y réduisait à la chaîne
     * vide, « SFR 123 » au code court « 123 », et `+33612345678` restait distinct de `0612345678`.
     * Changer de clé sans reprendre l'existant aurait **désarmé silencieusement tous les blocages
     * déjà posés** — le pire résultat possible pour ce correctif. La conversion repart de
     * `raw_number`, seul champ à avoir conservé la forme d'origine.
     *
     * La décision revient à [planBlocklistRekey], pur et testé ; il ne reste ici que l'application,
     * conduite pour qu'aucun incident ne retire une protection : une suppression de doublon n'a
     * lieu que si la clé qui le remplace a été **constatée présente**, soit qu'elle y était déjà,
     * soit que sa réécriture a réussi. Un échec d'écriture fait donc renoncer à la suppression
     * associée plutôt que de la laisser passer.
     *
     * `upsert` étant en `REPLACE` sur un index unique, réattribuer une clé peut évincer une entrée
     * pas encore parcourue qui la portait déjà : le résultat reste une entrée unique portant la
     * bonne clé, et la suppression prévue pour elle devient un `delete` sans effet.
     *
     * Sans drapeau de complétion, délibérément : la passe se réduit à la lecture d'une table de
     * quelques dizaines de lignes et n'écrit plus rien dès le second démarrage. Un drapeau aurait
     * imposé trois points de câblage supplémentaires dans les réglages pour aucun gain mesurable.
     */
    suspend fun rekeyLegacyEntries(): Int = withContext(io) {
        val entities = runCatching { blockedNumberDao.all() }.getOrDefault(emptyList())
        if (entities.isEmpty()) return@withContext 0
        val byId = entities.associateBy { it.id }
        val plan = planBlocklistRekey(
            entities.map {
                LegacyBlockEntry(
                    id = it.id,
                    rawNumber = it.rawNumber,
                    normalizedNumber = it.normalizedNumber,
                    createdAt = it.createdAt,
                )
            },
        )
        val present = HashSet<String>()
        var rekeyed = 0
        var collapsed = 0
        for (action in plan) {
            when (action) {
                is RekeyAction.Retain -> present.add(action.key)
                is RekeyAction.Update -> {
                    val entity = byId[action.id] ?: continue
                    runCatching { blockedNumberDao.upsert(entity.copy(normalizedNumber = action.key)) }
                        .onSuccess {
                            present.add(action.key)
                            rekeyed += 1
                        }
                        .onFailure { Timber.w(it, "Rekey: update of entry #%d failed", action.id) }
                }
                is RekeyAction.Collapse -> {
                    if (action.supersededBy !in present) {
                        Timber.w("Rekey: keeping duplicate #%d, its replacement is not in place", action.id)
                        continue
                    }
                    runCatching { blockedNumberDao.delete(action.id) }
                        .onSuccess { collapsed += 1 }
                        .onFailure { Timber.w(it, "Rekey: collapse of duplicate #%d failed", action.id) }
                }
            }
        }
        if (rekeyed > 0 || collapsed > 0) {
            Timber.i("Rekey: %d entries migrated, %d duplicates collapsed", rekeyed, collapsed)
        }
        rekeyed
    }

    /**
     * Walks the current conversation list (including archived) and deletes any whose **every**
     * participant matches a blocked number. The deletion cascades to messages via the Room FK and
     * to the system provider via [ConversationRepository.delete].
     *
     * v1.25.4 — rapprochement par [com.filestech.sms.core.ext.blockKey], la clé commune à
     * l'enregistrement, au filtre de réception et au marquage de la liste. Auparavant la purge
     * comparait des suffixes de 8 chiffres calculés sur `PhoneAddress.normalized` : « SFR2 » y
     * devenait « 2 », de sorte qu'une purge pouvait effacer des conversations que la liste
     * n'avait jamais signalées comme bloquées. Ce que l'utilisateur voit marqué est désormais
     * exactement ce que la purge emporte.
     *
     * On part de [com.filestech.sms.domain.model.PhoneAddress.raw] et non de `normalized`, qui a
     * déjà perdu les lettres dont [com.filestech.sms.core.ext.blockKey] a besoin.
     *
     * Returns the number of conversations actually purged so the caller (Settings action,
     * scheduled boot run) can surface a snackbar.
     */
    suspend fun purgeMatchingConversations(): Int = withContext(io) {
        // v1.27.2 (audit Codex du 2026-08-05, C-08) — cette passe SUPPRIME des conversations.
        // Sur une cle de neuf chiffres, elle pouvait donc effacer la conversation d'un
        // correspondant etranger qui n'a jamais ete bloque. Comparaison region-aware.
        val blockedRaw = repo.blockedRawSnapshot().filter { it.isNotBlank() }
        Timber.i("Purge: %d blocked entries", blockedRaw.size)
        if (blockedRaw.isEmpty()) return@withContext 0
        val isBlockedAddress = phoneIdentity.blockedMatcher(blockedRaw)
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
            val matchCount = addresses.count { isBlockedAddress(it.raw) }
            if (matchCount == 0) continue
            if (matchCount < addresses.size) {
                partialMatch += 1
                // v1.6.1 (audit SEC-05) — `addrs=%s` retiré : les clés de rapprochement sont des
                // quasi-identifiants PII (RGPD). En release R8 strip tout l'appel Timber via
                // assumenosideeffects mais en debug logcat les exposait à toute app détentrice
                // de READ_LOGS.
                Timber.d("Purge: conv #%d partial-block (%d/%d) — keeping", entity.id, matchCount, addresses.size)
                continue
            }
            Timber.i("Purge: deleting conv #%d (all %d participants blocked)", entity.id, addresses.size)
            runCatching { conversationRepo.delete(entity.id) }
                .onSuccess { purged += 1 }
                .onFailure { Timber.w(it, "Purge of blocked conv #%d failed", entity.id) }
        }
        Timber.i("Purge: result purged=%d partialMatch=%d", purged, partialMatch)
        purged
    }
}
