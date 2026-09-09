package com.filestech.sms.data.mms

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * v1.28.3 (F18) — **le seul défaut de la relecture externe (MR F-Droid !38458) qui détruise la
 * donnée d'une AUTRE application.**
 *
 * `purgeStaleOutbox` est un chien de garde : il nettoie les MMS restés en `msg_box = OUTBOX`
 * quand `MmsSentReceiver` n'a jamais tiré — processus tué en plein envoi, force-stop, Doze
 * suivi d'un redémarrage. Son sélecteur ne portait que sur l'état et sur l'âge :
 *
 * ```
 * "msg_box=? AND date<?"
 * ```
 *
 * Il ne demandait jamais QUI avait créé la ligne. Toute ligne du fournisseur système en OUTBOX
 * de plus de quinze minutes était donc supprimée — y compris celle d'une application
 * constructeur cohabitante, ou de l'application SMS utilisée avant SMS Tech, dont un MMS avait
 * échoué. Le chien de garde tourne toutes les douze heures.
 *
 * L'identifiant qui manquait existait déjà : `messages.mms_system_id`, que `MmsSentReceiver`
 * consulte précisément pour refuser d'agir sur une ligne qui n'est pas la sienne. La purge était
 * le seul chemin destructeur du dépôt à ne pas faire ce contrôle, alors qu'elle est le plus
 * large.
 *
 * # Pourquoi ces tests portent sur le sélecteur et non sur la classe
 *
 * `MmsSystemWriteback` dépend d'un `Context` et écrit dans `content://mms` : l'exercer
 * demanderait un émulateur où l'application est gestionnaire SMS par défaut. Le défaut, lui,
 * était **entièrement dans la construction du sélecteur**. Le tester là où il vivait donne un
 * garde-fou qui tourne à chaque build plutôt qu'un test d'appareil que personne ne lance.
 */
class MmsOutboxPurgeSelectorTest {

    private companion object {
        const val CUTOFF = 1_700_000_000L
        const val MSG_BOX_OUTBOX = "4"
    }

    /**
     * Le critère de propriété est présent, et il porte bien sur `_id`. C'est ce test qui tombe
     * si quelqu'un rétablit un sélecteur ne connaissant que l'état et l'âge.
     */
    @Test
    fun `le selecteur borne la suppression aux identifiants possedes`() {
        val (selection, args) = selecteurOutboxPossedee(CUTOFF, listOf(11L, 22L, 33L))

        assertThat(selection).isEqualTo("msg_box=? AND date<? AND _id IN (?,?,?)")
        assertThat(args.toList())
            .containsExactly(MSG_BOX_OUTBOX, CUTOFF.toString(), "11", "22", "33")
            .inOrder()
    }

    /**
     * Un identifiant étranger n'apparaît nulle part dans la requête — la formulation positive
     * du test ci-dessus ne le dirait pas à elle seule.
     */
    @Test
    fun `un identifiant qui ne nous appartient pas n est jamais vise`() {
        val etranger = 999L
        val (selection, args) = selecteurOutboxPossedee(CUTOFF, listOf(11L, 22L))

        assertThat(args.toList()).doesNotContain(etranger.toString())
        // Deux marques de paramètre pour deux identifiants : le lot ne s'élargit pas en chemin.
        assertThat(selection).contains("_id IN (?,?)")
    }

    /**
     * Le nombre de marques de paramètre suit exactement la taille du lot. Un décalage ferait
     * lever le fournisseur — ou, pire, décalerait les arguments et viserait d'autres lignes.
     */
    @Test
    fun `les marques de parametre suivent la taille du lot`() {
        for (taille in intArrayOf(1, 2, 7, 900)) {
            val lot = (1L..taille.toLong()).toList()
            val (selection, args) = selecteurOutboxPossedee(CUTOFF, lot)
            assertThat(selection.count { it == '?' }).isEqualTo(taille + 2)
            assertThat(args).hasLength(taille + 2)
        }
    }

    /**
     * **Contrôle négatif du correctif.** Un lot vide ne doit jamais produire de requête : sans
     * clause `IN`, la suppression redeviendrait globale et le défaut serait intégralement
     * rouvert. `purgeStaleOutbox` sort avant d'arriver ici ; cette garde existe pour qu'un futur
     * appelant ne puisse pas contourner ce choix par inadvertance.
     */
    @Test
    fun `un lot vide ne produit aucune requete`() {
        assertThrows<IllegalArgumentException> { selecteurOutboxPossedee(CUTOFF, emptyList()) }
    }
}
