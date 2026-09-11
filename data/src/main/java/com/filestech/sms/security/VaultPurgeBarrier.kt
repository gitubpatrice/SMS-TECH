package com.filestech.sms.security

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.28.4 (F13, suite de la relecture externe de la MR F-Droid !38458) — **la barrière d'écriture
 * pendant une purge du coffre.**
 *
 * La purge relit le coffre APRÈS sa boucle (F09) et ne retire le PIN que s'il est démontrablement
 * vide. Mais entre cette relecture et le retrait du PIN, rien n'empêchait une conversation
 * d'**entrer** au coffre : le PIN partait alors alors qu'une conversation protégée existait —
 * le coffre s'ouvrait sans second facteur. La relecture ne rend pas la fin de purge atomique
 * contre un écrivain concurrent, c'est le mot pour mot du constat.
 *
 * Une transaction ne peut pas couvrir ce cas — le PIN ne vit pas en base, et la purge appelle le
 * fournisseur système, qu'on ne tient pas sous un verrou SQLite. La barrière ferme la seule
 * porte qui compte : **on n'entre pas au coffre pendant qu'on le vide.** En sortir reste permis,
 * une sortie ne peut que réduire ce que la purge doit détruire.
 *
 * v1.28.5 (sixième note d'Andrew, point 2) — **la v1.28.4 était un test-puis-agir.** Une entrée
 * lisait `enCours`, puis écrivait en base plus tard ; une purge levée entre les deux ne la voyait
 * pas, et sa relecture finale de `remaining` pouvait précéder le commit de l'entrée. La barrière
 * est désormais linéarisée : une entrée s'inscrit **sous le même verrou** que celui qui lève la
 * purge, et la purge **attend** que les entrées inscrites avant elle soient commises avant de
 * balayer. Une entrée qui arrive après est refusée, comme avant. Le second trou — la barrière
 * retombait avant le retrait du PIN — se ferme dans `ConversationEraser.purgeVault`, qui exécute
 * la décision de l'appelant sous la barrière.
 *
 * En mémoire, volontairement : une purge ne survit pas au processus, une barrière durable
 * resterait levée après une mort du processus et bloquerait le coffre pour rien.
 */
@Singleton
class VaultPurgeBarrier @Inject constructor() {

    private val verrou = Mutex()
    private var purgeEnCours = false
    private var entreesEnVol = 0
    private var plusAucuneEntree: CompletableDeferred<Unit>? = null

    /** Lecture d'état, pour les tests et les journaux ; la décision, elle, se prend sous [verrou]. */
    val enCours: Boolean get() = purgeEnCours

    /**
     * Une ENTRÉE au coffre : refusée si une purge est levée ; sinon inscrite, exécutée, puis
     * désinscrite **quoi qu'il arrive** — une entrée jamais désinscrite ferait attendre la
     * prochaine purge indéfiniment. La désinscription est non annulable pour la même raison.
     */
    suspend fun <T> enEntrant(bloc: suspend () -> T): Outcome<T> {
        verrou.withLock {
            if (purgeEnCours) return Outcome.Failure(AppError.VaultPurging)
            entreesEnVol++
        }
        try {
            return Outcome.Success(bloc())
        } finally {
            withContext(NonCancellable) {
                verrou.withLock {
                    entreesEnVol--
                    if (entreesEnVol == 0) plusAucuneEntree?.complete(Unit)
                }
            }
        }
    }

    /**
     * Exécute [bloc] barrière levée, après que les entrées déjà inscrites ont fini, et l'abaisse
     * **quoi qu'il arrive** — une purge qui lève laisserait sinon le coffre fermé aux entrées
     * jusqu'au prochain démarrage.
     * Une purge déjà en cours refuse la seconde : deux balayages entrelacés compteraient double.
     */
    suspend fun <T> pendant(bloc: suspend () -> T): T {
        val attente = verrou.withLock {
            check(!purgeEnCours) { "purge du coffre deja en cours" }
            purgeEnCours = true
            if (entreesEnVol > 0) CompletableDeferred<Unit>().also { plusAucuneEntree = it } else null
        }
        try {
            attente?.await()
            return bloc()
        } finally {
            withContext(NonCancellable) {
                verrou.withLock {
                    purgeEnCours = false
                    plusAucuneEntree = null
                }
            }
        }
    }
}
