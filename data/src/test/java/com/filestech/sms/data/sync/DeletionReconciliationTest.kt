package com.filestech.sms.data.sync

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 🔴 v1.27.2 (audit Codex du 2026-08-05, C-03 / C-04) — **le seul chemin automatique qui efface
 * des messages n'avait aucun test.**
 *
 * Il vivait entier derrière un `ContentResolver`, donc hors de portée de la JVM, et une gate verte
 * n'en disait rien. Deux défauts y ont vécu, tous deux issus de la même erreur de raisonnement —
 * **déduire une suppression de l'absence dans une liste** :
 *
 *  - le garde-fou de proportionnalité ne se déclenchait qu'**au-dessus de 50 %**. Une lecture
 *    partielle rendant 60 identifiants sur 100 laissait donc effacer 40 messages valides,
 *    définitivement, sans la moindre vérification ;
 *  - la « seconde lecture » censée confirmer une suppression massive n'était pas une observation
 *    indépendante : même fournisseur, même projection, même processus, quelques millisecondes
 *    d'écart. Une troncature déterministe se reproduit à l'identique.
 *
 * Les tests ci-dessous tiennent les **deux bords**, et c'est essentiel : une garde qui ne
 * supprimerait plus rien serait tout aussi fausse. Un message que l'utilisateur a effacé ailleurs
 * — potentiellement sensible — doit finir par disparaître d'ici aussi.
 */
class DeletionReconciliationTest {

    private companion object {
        const val P = "content://sms/"
        val MIROIR = (1..100).map { "$P$it" }
    }

    /** Lecture globale saine : le fournisseur rend tout sauf [supprimes]. */
    private fun lectureComplete(supprimes: Set<Int> = emptySet()): Set<String> =
        (1..100).filterNot { it in supprimes }.map { "$P$it" }.toSet()

    /** Sonde honnête : seuls [supprimes] sont réellement absents. */
    private fun sondeHonnete(supprimes: Set<Int>): (String) -> Boolean? = { uri ->
        uri.removePrefix(P).toInt() !in supprimes
    }

    // ─────────────────── Le bord dangereux : ne pas effacer a tort ───────────────────

    /**
     * 🔴 **C-03, le cœur du constat.** Lecture partielle : le fournisseur ne rend que 60 lignes
     * sur 100, alors qu'aucune n'a été supprimée. L'ancienne garde voyait 40 % de manquants, donc
     * sous son seuil de 50 %, et **effaçait les 40**.
     */
    @Test
    fun `une lecture partielle n efface RIEN, meme sous le seuil de 50 pourcent`() {
        val partielle = (1..60).map { "$P$it" }.toSet()

        val d = DeletionReconciliation.decide(
            mirrored = MIROIR,
            aliveUris = partielle,
            pending = emptySet(),
            probe = sondeHonnete(emptySet()), // rien n'est réellement supprimé
        )

        assertThat(d.toDelete).isEmpty()
        assertThat(d.nextPending).isEmpty()
        // Non-vacuité : 40 candidats ont bien été soupçonnés, et tous ont été disculpés.
        assertThat(d.unverified).isEqualTo(40)
    }

    /**
     * 🔴 **C-04.** Deux passes successives sur la même lecture tronquée. L'ancienne version y
     * voyait une confirmation ; ce ne sont que deux fois la même erreur.
     */
    @Test
    fun `deux passes sur la meme troncature n effacent toujours rien`() {
        val tronquee = (1..20).map { "$P$it" }.toSet()
        val sonde = sondeHonnete(emptySet())

        val p1 = DeletionReconciliation.decide(MIROIR, tronquee, emptySet(), sonde)
        val p2 = DeletionReconciliation.decide(MIROIR, tronquee, p1.nextPending, sonde)

        assertThat(p1.toDelete).isEmpty()
        assertThat(p2.toDelete).isEmpty()
        assertThat(p1.unverified).isEqualTo(80)
    }

    /** Une sonde qui ne sait pas répondre (`null`) ne doit JAMAIS autoriser un effacement. */
    @Test
    fun `une sonde indeterminable n efface jamais`() {
        val d1 = DeletionReconciliation.decide(
            mirrored = MIROIR,
            aliveUris = lectureComplete(setOf(7)),
            pending = setOf("${P}7"), // déjà vue absente à la passe précédente
            probe = { null },
        )
        assertThat(d1.toDelete).isEmpty()
        assertThat(d1.nextPending).isEmpty()
    }

    /**
     * Le fournisseur rendu **entièrement vide** est le cas extrême d'une lecture douteuse. Il ne
     * bénéficie d'aucun régime de faveur : chaque ligne doit être prouvée absente, deux fois.
     */
    @Test
    fun `un fournisseur vide mais menteur n efface rien`() {
        val sonde = sondeHonnete(emptySet())
        val p1 = DeletionReconciliation.decide(MIROIR, emptySet(), emptySet(), sonde)
        val p2 = DeletionReconciliation.decide(MIROIR, emptySet(), p1.nextPending, sonde)

        assertThat(p1.toDelete).isEmpty()
        assertThat(p2.toDelete).isEmpty()
        assertThat(p1.unverified).isEqualTo(100)
    }

    // ─────────────────── L'autre bord : la suppression légitime DOIT converger ───────────────────

    /**
     * Trois messages réellement supprimés depuis une autre application. Une seule passe ne suffit
     * pas — c'est voulu — mais **la seconde les efface**. Sans cette convergence, des messages que
     * l'utilisateur a voulu supprimer resteraient affichés ici indéfiniment : ce n'est pas une
     * sécurité, c'est une fuite.
     */
    @Test
    fun `une suppression reelle converge en deux passes`() {
        val supprimes = setOf(4, 8, 15)
        val vue = lectureComplete(supprimes)
        val sonde = sondeHonnete(supprimes)

        val p1 = DeletionReconciliation.decide(MIROIR, vue, emptySet(), sonde)
        assertThat(p1.toDelete).isEmpty()
        assertThat(p1.nextPending).containsExactly("${P}4", "${P}8", "${P}15")
        assertThat(p1.unverified).isEqualTo(0)

        val p2 = DeletionReconciliation.decide(MIROIR, vue, p1.nextPending, sonde)
        assertThat(p2.toDelete).containsExactly("${P}4", "${P}8", "${P}15")
    }

    /**
     * 🔴 **La suppression massive LÉGITIME doit passer aussi.** L'ancienne garde la refusait à
     * chaque passe, indéfiniment : 80 messages effacés ailleurs restaient affichés ici — et dans
     * les aperçus — jusqu'à ce que de nouveaux messages diluent le ratio. Une garde qui fige
     * durablement ce que quelqu'un a voulu effacer est une fuite, pas une protection.
     */
    @Test
    fun `une suppression massive legitime converge aussi`() {
        val supprimes = (21..100).toSet() // 80 sur 100
        val vue = lectureComplete(supprimes)
        val sonde = sondeHonnete(supprimes)

        val p1 = DeletionReconciliation.decide(MIROIR, vue, emptySet(), sonde)
        val p2 = DeletionReconciliation.decide(MIROIR, vue, p1.nextPending, sonde)

        assertThat(p2.toDelete).hasSize(80)
        // Et le ratio n'entre nulle part dans la décision : 80 % passe, parce que chaque ligne a
        // été prouvée absente individuellement.
        assertThat(p2.unverified).isEqualTo(0)
    }

    /** Rien à réconcilier : la mémoire des passes précédentes est remise à plat. */
    @Test
    fun `sans candidat, rien n est efface ni retenu`() {
        val d = DeletionReconciliation.decide(
            mirrored = MIROIR,
            aliveUris = lectureComplete(),
            pending = setOf("${P}4"),
            probe = { error("la sonde ne doit jamais etre appelee") },
        )
        assertThat(d.toDelete).isEmpty()
        assertThat(d.nextPending).isEmpty()
    }

    /**
     * Une ligne réapparue entre deux passes — restauration, resynchronisation d'une autre
     * application — sort de la file d'attente au lieu d'être effacée.
     */
    @Test
    fun `une ligne revenue entre deux passes n est pas effacee`() {
        val p1 = DeletionReconciliation.decide(
            MIROIR,
            lectureComplete(setOf(9)),
            emptySet(),
            sondeHonnete(setOf(9)),
        )
        assertThat(p1.nextPending).containsExactly("${P}9")

        val p2 = DeletionReconciliation.decide(
            MIROIR,
            lectureComplete(), // la ligne est revenue
            p1.nextPending,
            sondeHonnete(emptySet()),
        )
        assertThat(p2.toDelete).isEmpty()
        assertThat(p2.nextPending).isEmpty()
    }

    /** Un URI illisible n'est jamais un candidat à l'effacement. */
    @Test
    fun `un uri non numerique n est jamais efface`() {
        val bancal = "content://sms/pas-un-nombre"
        val d = DeletionReconciliation.decide(
            mirrored = listOf(bancal),
            aliveUris = emptySet(),
            pending = setOf(bancal),
            probe = { true }, // ce que fait le manager pour un identifiant illisible
        )
        assertThat(d.toDelete).isEmpty()
    }
}
