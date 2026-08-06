package com.filestech.sms.domain.safetycall.journal

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.28.0 — verrouille le bornage du journal **par cycles entiers**.
 *
 * # Ce qui compte ici
 *
 * La propriété à protéger n'est pas « le fichier reste petit » — n'importe quelle troncature y
 * suffirait. C'est **« un cycle conservé l'est en entier »**. Un cycle amputé de sa ligne `TRIGGER`
 * ou de sa dernière ligne `NEXT` répond « il manque des lignes » à la question « qu'a fait le
 * moteur », et le journal perd sa raison d'être.
 *
 * Le test vérifie donc les deux sens : ce qui doit partir part, et ce qui reste reste **complet**.
 */
class SafetyCallJournalRotationTest {

    private fun cycleLines(generation: Long, count: Int = 3): List<String> =
        (1..count).map { index ->
            SafetyCallJournalEntry(
                wallMs = generation * 1_000L + index,
                elapsedMs = generation * 100L + index,
                generation = generation,
                claimId = generation,
                event = SafetyCallJournalEvent.WAKE,
                subject = "etape$index",
            ).format()
        }

    private fun generationsOf(lines: List<String>): List<Long> =
        lines.mapNotNull { SafetyCallJournalEntry.parse(it)?.generation }.distinct()

    @Test
    fun `les cycles les plus anciens partent, les plus recents restent`() {
        val lines = (1L..8L).flatMap { cycleLines(it) }

        val kept = SafetyCallJournalRotation.prune(lines, maxCycles = 3)

        assertThat(generationsOf(kept)).containsExactly(6L, 7L, 8L).inOrder()
    }

    @Test
    fun `un cycle conserve l'est en entier`() {
        val lines = (1L..4L).flatMap { cycleLines(it, count = 5) }

        val kept = SafetyCallJournalRotation.prune(lines, maxCycles = 2)

        // 2 cycles × 5 lignes : aucune ligne manquante à l'intérieur des cycles gardés.
        assertThat(kept).hasSize(10)
        assertThat(kept).containsExactlyElementsIn(cycleLines(3L, 5) + cycleLines(4L, 5)).inOrder()
    }

    @Test
    fun `en dessous du plafond rien n'est retire`() {
        val lines = (1L..3L).flatMap { cycleLines(it) }

        val kept = SafetyCallJournalRotation.prune(lines, maxCycles = 5)

        assertThat(kept).isEqualTo(lines)
    }

    @Test
    fun `les lignes illisibles sont ecartees et ne comptent pas comme un cycle`() {
        val lines = cycleLines(1L) + listOf("", "n'importe quoi", "1|2|3") + cycleLines(2L)

        val kept = SafetyCallJournalRotation.prune(lines, maxCycles = 2)

        assertThat(kept).containsExactlyElementsIn(cycleLines(1L) + cycleLines(2L)).inOrder()
        assertThat(generationsOf(kept)).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `un journal vide ou entierement illisible rend une liste vide`() {
        assertThat(SafetyCallJournalRotation.prune(emptyList())).isEmpty()
        assertThat(SafetyCallJournalRotation.prune(listOf("", "abc", "1|2"))).isEmpty()
    }

    @Test
    fun `le garde-fou en octets mord sur un cycle pathologique`() {
        // Un unique cycle en boucle : le bornage par cycles ne peut rien retirer, c'est au plafond
        // en octets de contenir la croissance.
        val lines = cycleLines(1L, count = 200)

        val kept = SafetyCallJournalRotation.prune(lines, maxCycles = 5, maxBytes = 400)

        assertThat(kept.size).isLessThan(lines.size)
        // Ce sont les lignes les plus RÉCENTES qui survivent : sur une boucle, l'état courant
        // renseigne plus que son point de départ.
        assertThat(kept.last()).isEqualTo(lines.last())
    }

    @Test
    fun `le resultat tient toujours dans le budget en octets`() {
        // ⚠️ C'est l'invariant de **convergence**, et c'est le défaut qu'une première version portait :
        // le plafond était exprimé en nombre de lignes alors que le champ « détails » est de longueur
        // libre, donc aucun nombre de lignes ne bornait une taille. L'élagage ne ramenait jamais le
        // fichier sous le seuil qui le déclenche, et chaque écriture relisait puis réécrivait tout.
        //
        // Des lignes volontairement longues ET porteuses de caractères multi-octets : `‥` pèse trois
        // octets pour un caractère, donc un budget compté en `length` serait sous-estimé ici.
        val heavy = (1..40).map { index ->
            SafetyCallJournalEntry(
                wallMs = index.toLong(),
                elapsedMs = index.toLong(),
                generation = 1L,
                claimId = 1L,
                event = SafetyCallJournalEvent.SEND,
                subject = "1/1",
                details = "to=a3f1‥41 " + "z".repeat(200),
            ).format()
        }
        val budget = 2_000

        val kept = SafetyCallJournalRotation.prune(heavy, maxBytes = budget)

        val bytes = kept.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 }
        assertThat(bytes).isAtMost(budget)
        assertThat(kept).isNotEmpty()
    }

    @Test
    fun `un budget nul rend une liste vide plutot qu'une ligne de trop`() {
        assertThat(SafetyCallJournalRotation.prune(cycleLines(1L), maxBytes = 0)).isEmpty()
    }

    @Test
    fun `une ligne unique plus grosse que le budget est ecartee et ne le depasse pas`() {
        val huge = SafetyCallJournalEntry(
            wallMs = 1L,
            elapsedMs = 1L,
            generation = 1L,
            claimId = 1L,
            event = SafetyCallJournalEvent.WAKE,
            subject = "x",
            details = "w".repeat(500),
        ).format()

        val kept = SafetyCallJournalRotation.prune(listOf(huge), maxBytes = 100)

        // Mieux vaut un journal vide qu'un journal qui ignore sa propre borne : c'est le dépassement
        // qui empêche la convergence.
        assertThat(kept).isEmpty()
    }

    @Test
    fun `l'ordre d'apparition prime sur l'ordre numerique`() {
        // Fichier venu d'ailleurs, générations dans un ordre inattendu : on garde les DERNIÈRES
        // apparues, parce qu'un journal en ajout seul se lit dans son ordre d'écriture.
        val lines = cycleLines(9L) + cycleLines(2L) + cycleLines(5L)

        val kept = SafetyCallJournalRotation.prune(lines, maxCycles = 2)

        assertThat(generationsOf(kept)).containsExactly(2L, 5L).inOrder()
    }

    @Test
    fun `les valeurs par defaut sont celles annoncees`() {
        assertThat(SafetyCallJournalRotation.MAX_CYCLES).isEqualTo(5)
        val lines = (1L..7L).flatMap { cycleLines(it) }

        val kept = SafetyCallJournalRotation.prune(lines)

        assertThat(generationsOf(kept)).hasSize(SafetyCallJournalRotation.MAX_CYCLES)
    }
}
