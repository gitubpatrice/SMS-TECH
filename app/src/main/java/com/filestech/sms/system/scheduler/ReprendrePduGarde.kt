package com.filestech.sms.system.scheduler

import com.filestech.sms.core.io.LectureBornee
import com.filestech.sms.data.mms.PdusEnAttente
import com.filestech.sms.pdu.PduParser
import com.filestech.sms.pdu.RetrieveConf
import com.filestech.sms.system.receiver.TraitementMmsRecu
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * v1.28.9 (F17) — **rouvre UN PDU gardé** et le passe au traitement du receveur ([TraitementMmsRecu]).
 *
 * Ce que la reprise ne peut pas faire, elle le dit par son [Sort] plutôt que de deviner :
 * - trop gros, ou qui n'est pas un `RetrieveConf` : il ne le deviendra pas — [Sort.CONSOMME], comme au
 *   receveur ;
 * - pas lu (erreur d'entrée-sortie) : peut-être à la passe suivante — [Sort.A_REESSAYER] ;
 * - sans expéditeur : le receveur avait l'indice de la notification WAP-Push, la reprise ne l'a plus, et une
 *   conversation à l'adresse vide serait pire que rien — [Sort.ABANDONNE], le balayage de 24 h l'emportera ;
 * - traité : consommé, sauf si le traitement demande de le garder, un média n'étant toujours pas écrit.
 */
class ReprendrePduGarde @Inject constructor(
    private val traitement: TraitementMmsRecu,
) {

    enum class Sort { CONSOMME, A_REESSAYER, ABANDONNE }

    suspend fun reprendre(fichier: File, nom: PdusEnAttente.Nom): Sort {
        val taille = fichier.length()
        val tropGros = taille > PdusEnAttente.PLAFOND_OCTETS
        val octets = if (tropGros) null else LectureBornee.lire(fichier, PdusEnAttente.PLAFOND_OCTETS)
        val conf = octets?.let { runCatching { PduParser(it).parse() }.getOrNull() } as? RetrieveConf
        return when {
            tropGros -> Sort.CONSOMME.also { Timber.w("Reprise MMS: PDU trop gros (%d o), ecarte", taille) }
            octets == null -> Sort.A_REESSAYER
            conf == null -> Sort.CONSOMME.also { Timber.w("Reprise MMS: PDU illisible, ecarte") }
            conf.from?.string.isNullOrBlank() ->
                Sort.ABANDONNE.also { Timber.w("Reprise MMS: PDU sans expediteur, laisse au balayage") }
            traitement.traiter(conf, nom.cle, nom.subId, indiceExpediteur = null) { fichier.exists() }.garderLePdu ->
                Sort.A_REESSAYER
            else -> Sort.CONSOMME
        }
    }
}
