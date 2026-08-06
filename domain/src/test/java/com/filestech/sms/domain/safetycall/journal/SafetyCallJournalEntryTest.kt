package com.filestech.sms.domain.safetycall.journal

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.28.0 — verrouille le format d'une ligne du journal technique.
 *
 * # Ce qui compte ici
 *
 * Deux propriétés, et elles ne sont pas de même nature.
 *
 * **Le décalage de champs est le risque grave.** Un `|` ou un `\n` laissé passer dans un champ libre
 * ne casse pas seulement sa ligne : il en fabrique une seconde, syntaxiquement valide et fausse. Sur
 * un journal qui sert à établir qu'aucun proche n'a reçu deux fois la même relance, une ligne
 * inventée est pire qu'une ligne perdue. C'est pourquoi l'assainissement est testé sur les deux
 * champs libres, séparateur **et** saut de ligne.
 *
 * **La tolérance en lecture est l'autre moitié.** Le fichier peut avoir été tronqué par un arrêt
 * brutal ou écrit par un format antérieur. [SafetyCallJournalEntry.parse] doit alors rendre `null`,
 * jamais lever, et surtout ne jamais **ranger** une ligne douteuse dans une valeur par défaut : une
 * ligne mal classée serait comptée.
 */
class SafetyCallJournalEntryTest {

    private companion object {
        const val WALL = 1_786_017_018_487L
        const val ELAPSED = 54_680_340L
    }

    private fun entry(
        event: SafetyCallJournalEvent = SafetyCallJournalEvent.SEND,
        subject: String = "1/2",
        details: String = "to=a3f1‥41 ok",
    ) = SafetyCallJournalEntry(
        wallMs = WALL,
        elapsedMs = ELAPSED,
        generation = 7L,
        claimId = 142L,
        event = event,
        subject = subject,
        details = details,
    )

    @Test
    fun `la mise en forme produit sept champs separes par une barre`() {
        val line = entry().format()

        assertThat(line).isEqualTo("$WALL|$ELAPSED|7|142|SEND|1/2|to=a3f1‥41 ok")
    }

    @Test
    fun `la ligne ne contient jamais de saut de ligne`() {
        val line = entry(subject = "a\nb", details = "c\r\nd").format()

        assertThat(line).doesNotContain("\n")
        assertThat(line).doesNotContain("\r")
    }

    @Test
    fun `un separateur dans un champ libre est retire et ne decale rien`() {
        val line = entry(subject = "1|2", details = "to=x|y ok").format()

        // Sept champs exactement : si le `|` avait survécu, il y en aurait neuf.
        assertThat(line.count { it == '|' }).isEqualTo(6)
        val reread = SafetyCallJournalEntry.parse(line)
        assertThat(reread?.subject).isEqualTo("12")
        assertThat(reread?.details).isEqualTo("to=xy ok")
    }

    @Test
    fun `un aller-retour preserve tous les champs`() {
        val original = entry()

        val reread = SafetyCallJournalEntry.parse(original.format())

        assertThat(reread).isEqualTo(original)
    }

    @Test
    fun `le separateur visuel de l'empreinte survit a l'assainissement`() {
        // Le jeton de [SafetyCallJournalRedactor] contient U+2025. S'il était strippé, tous les
        // jetons se confondraient en un seul mot et la comparaison de destinataires deviendrait
        // illisible — silencieusement.
        val reread = SafetyCallJournalEntry.parse(entry(details = "to=a3f1‥41").format())

        assertThat(reread?.details).isEqualTo("to=a3f1‥41")
    }

    @Test
    fun `tous les evenements font un aller-retour`() {
        SafetyCallJournalEvent.entries.forEach { event ->
            val reread = SafetyCallJournalEntry.parse(entry(event = event).format())

            assertThat(reread?.event).isEqualTo(event)
        }
    }

    @Test
    fun `une ligne tronquee est rejetee et non completee`() {
        assertThat(SafetyCallJournalEntry.parse("$WALL|$ELAPSED|7|142|SEND")).isNull()
        assertThat(SafetyCallJournalEntry.parse("")).isNull()
        assertThat(SafetyCallJournalEntry.parse("n'importe quoi")).isNull()
    }

    @Test
    fun `un evenement inconnu fait rejeter la ligne plutot que la ranger ailleurs`() {
        val line = "$WALL|$ELAPSED|7|142|TELEPORT|x|y"

        assertThat(SafetyCallJournalEntry.parse(line)).isNull()
    }

    @Test
    fun `un champ numerique illisible fait rejeter la ligne`() {
        assertThat(SafetyCallJournalEntry.parse("abc|$ELAPSED|7|142|WAKE|x|y")).isNull()
        assertThat(SafetyCallJournalEntry.parse("$WALL|abc|7|142|WAKE|x|y")).isNull()
        assertThat(SafetyCallJournalEntry.parse("$WALL|$ELAPSED|abc|142|WAKE|x|y")).isNull()
        assertThat(SafetyCallJournalEntry.parse("$WALL|$ELAPSED|7|abc|WAKE|x|y")).isNull()
    }

    @Test
    fun `un detail vide reste lisible`() {
        val reread = SafetyCallJournalEntry.parse(entry(details = "").format())

        assertThat(reread?.details).isEmpty()
        assertThat(reread?.subject).isEqualTo("1/2")
    }

    @Test
    fun `un detail contenant des barres surnumeraires garde son texte a la relecture`() {
        // Cas d'un fichier venu d'ailleurs : [format] interdit ces barres, mais [parse] doit rendre
        // le septième champ entier plutôt que de le tronquer au premier séparateur rencontré.
        val reread = SafetyCallJournalEntry.parse("$WALL|$ELAPSED|7|142|WAKE|x|a|b|c")

        assertThat(reread?.details).isEqualTo("a|b|c")
    }
}
