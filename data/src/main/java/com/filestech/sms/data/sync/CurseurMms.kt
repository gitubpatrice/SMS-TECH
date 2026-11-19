package com.filestech.sms.data.sync

import com.filestech.sms.data.sms.MMS_MSG_BOX_DRAFT
import com.filestech.sms.data.sms.MMS_MSG_BOX_INBOX
import com.filestech.sms.data.sms.MMS_MSG_BOX_SENT
import com.filestech.sms.data.sms.TelephonyReader.EtatMms

/**
 * 2026-11-19 (issue #98, plusieurs profils) — **jusqu'où l'import des nouveaux MMS avance son curseur, et ce qu'il
 * doit relire plus tard.**
 *
 * L'import lit les lignes de `content://mms` d'identifiant supérieur au curseur. Une ligne qui n'est pas TERMINÉE —
 * brouillon, envoi en cours ou en échec d'un autre profil, copie d'un MMS reçu en cours d'écriture — ne s'importe
 * pas : importée maintenant, elle resterait « en attente » ou « en échec » ici pour toujours, ou incomplète. Mais une
 * ligne de `content://mms` garde son `_id` quand elle change de boîte (un brouillon envoyé, un échec renvoyé, une
 * copie achevée) : passée par le curseur, elle ne serait jamais relue.
 *
 * D'où la règle (relectures Gemini, GPT et Claude du 2026-11-19, qui ont toutes trois vu le brouillon sauté) : le
 * curseur avance toujours jusqu'à la dernière ligne lue, et chaque ligne non terminée qu'il passe entre dans une
 * liste d'ATTENTE (`AdvancedSettings.mmsNonTermines`, [IdDate] = l'identifiant et le moment où on l'a vue). À chaque
 * passe, la liste est relue : une ligne terminée s'importe et en sort ; une ligne disparue en sort ; une ligne
 * toujours non terminée y reste [ATTENTE_MAX_MS] au plus.
 *
 * Seules la réception et l'envoi abouti sont terminés. Un échec ne l'est pas : une autre application peut le
 * renvoyer sous le même identifiant (relecture Claude, 2e vague) ; SMS Tech, lui, efface la ligne d'un envoi échoué.
 *
 * La liste est bornée à [TAILLE_MAX] (relecture GPT, 2e vague : borner, c'est perdre ce qu'on retire). Au-delà, ce
 * sont les BROUILLONS les plus anciens qui partent d'abord — les seules lignes qu'une application peut garder
 * indéfiniment ; un envoi en cours finit toujours, en quelques minutes.
 *
 * Pure, pour être testée sans appareil (`CurseurMmsTest`).
 */
internal object CurseurMms {

    /** Un brouillon peut dormir longtemps ; au-delà d'un mois, on ne l'attend plus. */
    const val ATTENTE_MAX_MS: Long = 30L * 24L * 60L * 60L * 1_000L

    /** Les brouillons les plus anciens partent d'abord au-delà. Mille entrées, une vingtaine de kilo-octets. */
    const val TAILLE_MAX: Int = 1_000

    /** Les boîtes dont un MMS ne bouge plus : les seules que l'import prend (cf. `TelephonyReader.TrancheMms`). */
    val BOITES_TERMINEES: Set<Int> = setOf(MMS_MSG_BOX_INBOX, MMS_MSG_BOX_SENT)

    /**
     * @property jusqua le nouveau curseur, jamais inférieur à l'ancien.
     * @property aImporter les lignes de la liste d'attente qui sont terminées : à importer par leur identifiant.
     * @property attente la nouvelle liste d'attente.
     */
    data class Avance(val jusqua: Long, val aImporter: Set<Long>, val attente: List<IdDate>)

    /**
     * @param apres le curseur actuel.
     * @param nouveaux les lignes d'identifiant supérieur à [apres], dans n'importe quel ordre.
     * @param attente la liste d'attente actuelle.
     * @param etatsDeLAttente l'état ACTUEL des lignes de [attente] ; une ligne absente a été supprimée.
     */
    fun avancer(
        apres: Long,
        nouveaux: List<EtatMms>,
        attente: List<IdDate>,
        etatsDeLAttente: List<EtatMms>,
        maintenantMs: Long,
    ): Avance {
        val jusqua = maxOf(apres, nouveaux.maxOfOrNull { it.id } ?: apres)
        val boiteActuelle = etatsDeLAttente.associate { it.id to it.boite }
        val aImporter = HashSet<Long>()
        val gardes = ArrayList<Pair<IdDate, Int>>()
        for (enAttente in attente.distinctBy { it.id }) {
            val boite = boiteActuelle[enAttente.id] ?: continue
            when {
                boite in BOITES_TERMINEES -> aImporter += enAttente.id
                maintenantMs - enAttente.ms < ATTENTE_MAX_MS -> gardes += enAttente to boite
            }
        }
        val dejaSuivies = gardes.mapTo(HashSet()) { it.first.id }
        for (etat in nouveaux) {
            if (etat.id > apres && etat.boite !in BOITES_TERMINEES && dejaSuivies.add(etat.id)) {
                gardes += IdDate(etat.id, maintenantMs) to etat.boite
            }
        }
        val brouillonsEnDernier = compareBy<Pair<IdDate, Int>> { it.second == MMS_MSG_BOX_DRAFT }
            .thenByDescending { it.first.ms }
        val bornee = gardes.sortedWith(brouillonsEnDernier).take(TAILLE_MAX).map { it.first }
        return Avance(jusqua = jusqua, aImporter = aImporter, attente = bornee)
    }

    /**
     * Ajoute [ids] à [attente], vus [maintenantMs] — une ligne déjà en attente garde sa première date —, borné à
     * [TAILLE_MAX] par ancienneté. Pour l'import complet, qui met en attente les lignes non terminées qu'il passe, et
     * pour une ligne terminée qui n'a pas pu être lue (relecture Claude, 2e vague).
     */
    fun ajouter(attente: List<IdDate>, ids: Collection<Long>, maintenantMs: Long): List<IdDate> {
        val parId = LinkedHashMap<Long, IdDate>()
        for (entree in attente) parId.putIfAbsent(entree.id, entree)
        for (id in ids) parId.putIfAbsent(id, IdDate(id, maintenantMs))
        return parId.values.sortedByDescending { it.ms }.take(TAILLE_MAX)
    }
}
