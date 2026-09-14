package com.filestech.sms.system.scheduler

import com.filestech.sms.core.result.runCatchingCancellable
import com.filestech.sms.data.mms.PdusEnAttente
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * v1.28.9 (F17, septième note d'Andrew sur la MR !38458) — **une passe de reprise des PDU gardés.**
 *
 * # Ce qui manquait
 *
 * Le receveur gardait le PDU d'un MMS dont un média n'avait pas pu être écrit, ou dont le traitement avait
 * échoué — c'est la seule copie du message —, mais personne ne le rouvrait : le balayage de 24 h de
 * [TelephonySyncWorker] l'emportait.
 *
 * # Les règles d'une passe
 *
 * Pour chaque `*.pdu` du dossier des PDU entrants :
 * - vide, ou sans clé (écrit avant la 1.28.9, ou notification sans `transactionId`) : rien ne reconnaîtrait
 *   un message déjà écrit — laissé au balayage, jamais repris ;
 * - de moins de dix minutes : le receveur est peut-être encore dessus — à réessayer, sans y toucher ;
 * - repris et consommé : effacé ;
 * - à réessayer : tant qu'il a moins de vingt heures, puis laissé au balayage.
 *
 * Rien n'est effacé ici qui n'ait été consommé : un fichier laissé part avec le balayage, au bout de 24 h.
 */
class RepriseMms internal constructor(
    private val dossier: () -> File,
    private val reprendre: suspend (File, PdusEnAttente.Nom) -> ReprendrePduGarde.Sort,
) {

    @Inject constructor(pdus: PdusEnAttente, reprise: ReprendrePduGarde) :
        this({ pdus.dossier }, { fichier, nom -> reprise.reprendre(fichier, nom) })

    /** Le compte d'une passe. [aReessayer] non nul : une passe suivante a du travail. */
    data class Bilan(val repris: Int, val aReessayer: Int, val laisses: Int)

    private enum class Decision { REPRIS, A_REESSAYER, LAISSE }

    suspend fun passe(maintenant: Long): Bilan {
        val fichiers = dossier().listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) }.orEmpty()
        val decisions = fichiers.map { decider(it, maintenant) }
        return Bilan(
            repris = decisions.count { it == Decision.REPRIS },
            aReessayer = decisions.count { it == Decision.A_REESSAYER },
            laisses = decisions.count { it == Decision.LAISSE },
        )
    }

    private suspend fun decider(fichier: File, maintenant: Long): Decision {
        val age = maintenant - fichier.lastModified()
        val nom = PdusEnAttente.lireNom(fichier.name)?.takeIf { it.cle != null && fichier.length() > 0L }
        return when {
            nom == null -> Decision.LAISSE
            age < AGE_MINIMAL_MS -> Decision.A_REESSAYER
            else -> when (tenter(fichier, nom)) {
                ReprendrePduGarde.Sort.CONSOMME -> {
                    if (!fichier.delete() && fichier.exists()) Timber.w("Reprise MMS: PDU consomme non efface")
                    Decision.REPRIS
                }
                ReprendrePduGarde.Sort.A_REESSAYER ->
                    if (age < AGE_MAXIMAL_MS) Decision.A_REESSAYER else Decision.LAISSE
                ReprendrePduGarde.Sort.ABANDONNE -> Decision.LAISSE
            }
        }
    }

    /** Une exception garde le PDU pour la passe suivante ; une annulation, elle, remonte. */
    private suspend fun tenter(fichier: File, nom: PdusEnAttente.Nom): ReprendrePduGarde.Sort =
        runCatchingCancellable { reprendre(fichier, nom) }
            .onFailure { Timber.w(it, "Reprise MMS: traitement echoue, PDU garde") }
            .getOrDefault(ReprendrePduGarde.Sort.A_REESSAYER)

    private companion object {
        const val EXTENSION = ".pdu"

        /** Le temps que le receveur ait fini, ou que le système ait fini d'écrire le fichier. */
        const val AGE_MINIMAL_MS = 10L * 60 * 1000

        /** Sous les 24 h du balayage : la dernière tentative ne court pas après un fichier qui s'en va. */
        const val AGE_MAXIMAL_MS = 20L * 60 * 60 * 1000
    }
}
