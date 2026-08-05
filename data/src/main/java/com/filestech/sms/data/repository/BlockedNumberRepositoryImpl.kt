package com.filestech.sms.data.repository

import android.os.Build
import com.filestech.sms.core.ext.blockKey
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.blocking.BlockedNumberSystem
import com.filestech.sms.data.local.db.dao.BlockedNumberDao
import com.filestech.sms.data.local.db.entity.BlockedNumberEntity
import com.filestech.sms.data.local.db.mapper.toDomain
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.model.BlockedNumber
import com.filestech.sms.domain.repository.BlockedNumberRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BlockedNumberRepositoryImpl @Inject constructor(
    private val dao: BlockedNumberDao,
    private val system: BlockedNumberSystem,
    private val phoneIdentity: com.filestech.sms.data.sms.PhoneIdentity,
    @IoDispatcher private val io: CoroutineDispatcher,
) : BlockedNumberRepository {

    override fun observe(): Flow<List<BlockedNumber>> =
        dao.observe().map { list -> list.map { it.toDomain() } }.flowOn(io)

    /**
     * v1.25.4 — [com.filestech.sms.core.ext.blockKey] remplace `normalizePhone()` sur **tous** les
     * accès à la table, et c'est la même fonction que celle du marquage dans la liste.
     *
     * La v1.25.3 en avait deux : une égalité stricte ici, un rapprochement permissif à l'affichage.
     * Un numéro saisi en `+33…` dans les Réglages s'affichait donc « Bloqué » sur une conversation
     * enregistrée en `0…`, pendant que ce `isBlocked` répondait `false` et laissait les messages
     * arriver. La clé partagée supprime l'écart au lieu de le rattraper.
     */
    /**
     * v1.27.2 (audit Codex du 2026-08-05, C-08) — 🔴 LA CLE SEULE NE PROUVE RIEN.
     *
     * `blockKey` retient neuf chiffres, qui ne portent aucune information de pays : bloquer
     * `+33612345678` faisait rejeter les SMS de `+15612345678`. Et comme le curseur d'import
     * avance sur les lignes rejetees, le message de ce tiers non bloque n'etait pas seulement
     * ecarte, il etait **perdu definitivement**.
     *
     * La cle reste l'index — c'est elle qui evite de canonicaliser toute la liste a chaque
     * message — mais la decision se prend sur la forme BRUTE enregistree, canonicalisee en E.164
     * avec la region de la SIM. Aucune migration : `raw_number` est deja stocke.
     */
    override suspend fun isBlocked(rawNumber: String): Boolean = withContext(io) {
        val ident = phoneIdentity.snapshot()
        val key = ident.key(rawNumber)
        if (key.isEmpty()) return@withContext false
        if (dao.isBlocked(key)) return@withContext true
        // 🔴 v1.27.2 — CHEMIN DE TRANSITION, tant que le rekey n'a pas tourné.
        //
        // La clé stockée devient l'E.164, mais les entrées héritées portent encore le suffixe de
        // neuf chiffres jusqu'à la première synchronisation. Or un SMS peut arriver AVANT :
        // c'est même lui qui démarre le processus. La comparaison exacte ci-dessus ne trouverait
        // alors rien, et **un numéro bloqué passerait** — une régression de sécurité introduite
        // par le correctif lui-même.
        //
        // ⚠️ On ne se rabat PAS sur une simple égalité de suffixe : ce serait rouvrir la collision
        // internationale, et bloquer à tort coûte plus cher que laisser passer — le curseur
        // d'import avance sur la ligne rejetée, donc le message d'un tiers serait perdu
        // définitivement. On relit donc les entrées du seau et on tranche sur leur forme BRUTE,
        // avec la même règle d'identité que partout ailleurs.
        val legacyKey = rawNumber.blockKey()
        if (legacyKey.isEmpty() || legacyKey == key) return@withContext false
        dao.findAllByNormalized(legacyKey).any { ident.matches(it.rawNumber, rawNumber) }
    }

    override suspend fun block(rawNumber: String, label: String?): Outcome<Unit> = withContext(io) {
        if (rawNumber.isBlank()) return@withContext Outcome.Failure(AppError.Validation("number is blank"))
        // 🔴 v1.27.2 (audit Codex final, F-03) — la cle STOCKEE est l'identite E.164.
        //
        // L'index sur `normalized_number` est UNIQUE. Avec la cle de neuf chiffres,
        // `+33612345678` et `+15612345678` la partageaient : bloquer le second EVINCAIT
        // silencieusement le premier, et en debloquer un retirait la protection de l'autre.
        // Une cle E.164 les separe, et l'index redevient correct SANS migration — le rekey
        // existant convertit les entrees heritees a la premiere synchronisation.
        val normalized = phoneIdentity.snapshot().key(rawNumber)
        if (normalized.isEmpty()) {
            return@withContext Outcome.Failure(AppError.Validation("identite indeterminable"))
        }
        val systemUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) system.block(rawNumber) else null
        dao.upsert(
            BlockedNumberEntity(
                normalizedNumber = normalized,
                rawNumber = rawNumber,
                label = label,
                createdAt = System.currentTimeMillis(),
                systemUri = systemUri,
            ),
        )
        Outcome.Success(Unit)
    }

    override suspend fun unblock(rawNumber: String): Outcome<Unit> = withContext(io) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) system.unblock(rawNumber)
        // 🔴 v1.27.2 (audit Codex du 2026-08-05, LP-01) — LE JUMEAU DE `isBlocked`, QUE J AVAIS
        // OUBLIE.
        //
        // J avais ajoute le chemin de transition a la LECTURE sans le porter a l ECRITURE. Tant
        // que le rekey n a pas tourne, la ligne heritee porte encore le suffixe de neuf chiffres :
        // supprimer la seule cle E.164 ne trouvait rien. Le systeme etait bien debloque, la ligne
        // Room restait — et au rekey suivant elle redevenait active. **Le deblocage semblait sans
        // effet, et le blocage revenait tout seul.** Il fallait debloquer deux fois.
        //
        // Le motif du jumeau asymetrique, une fois de plus, dans le correctif meme qui le traitait.
        val ident = phoneIdentity.snapshot()
        val key = ident.key(rawNumber)
        dao.deleteByNormalized(key)
        val legacyKey = rawNumber.blockKey()
        if (legacyKey.isNotEmpty() && legacyKey != key) {
            // On ne supprime que les entrees heritees dont la forme BRUTE designe bien ce
            // correspondant : le seau de neuf chiffres peut en contenir d autres, d un autre pays.
            dao.findAllByNormalized(legacyKey)
                .filter { ident.matches(it.rawNumber, rawNumber) }
                .forEach { dao.delete(it.id) }
        }
        Outcome.Success(Unit)
    }

    override suspend fun mirrorFromSystem(rawNumber: String): Outcome<Unit> = withContext(io) {
        if (rawNumber.isBlank()) return@withContext Outcome.Failure(AppError.Validation("number is blank"))
        val normalized = phoneIdentity.snapshot().key(rawNumber)
        if (normalized.isEmpty()) return@withContext Outcome.Success(Unit)
        // No-op when already mirrored — keeps `created_at` stable and avoids a write storm at
        // boot when nothing has changed since last launch.
        if (dao.isBlocked(normalized)) return@withContext Outcome.Success(Unit)
        dao.upsert(
            BlockedNumberEntity(
                normalizedNumber = normalized,
                rawNumber = rawNumber,
                label = null,
                createdAt = System.currentTimeMillis(),
                // Deliberately null: we did NOT call `system.block()` here, the entry already
                // exists in the OS provider.
                systemUri = null,
            ),
        )
        Outcome.Success(Unit)
    }

    override suspend fun blockedNormalizedSnapshot(): Set<String> = withContext(io) {
        dao.allNormalized().toHashSet()
    }

    override suspend fun blockedRawSnapshot(): List<String> = withContext(io) {
        dao.allRaw()
    }
}
