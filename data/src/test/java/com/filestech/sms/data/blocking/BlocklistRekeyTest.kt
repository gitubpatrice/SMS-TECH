package com.filestech.sms.data.blocking

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.25.4 — la conversion des clés de liste noire est la seule partie du correctif capable de
 * **retirer une protection**. Une entrée oubliée sur son ancienne clé n'est plus interrogée par
 * personne : le correspondant repasse, et rien à l'écran ne le signale. Ces tests couvrent la
 * décision ; l'exécution ne fait que l'appliquer.
 */
class BlocklistRekeyTest {

    /**
     * v1.27.2 (audit Codex final, F-03) — la cle est desormais INJECTEE : elle vaut l'E.164 quand
     * la region permet de la calculer, ce qui separe enfin deux correspondants de pays differents
     * qui partageaient leur suffixe de neuf chiffres. Doublure francaise, comme dans
     * `PhoneAddressesMatchTest`.
     */
    private fun cleFr(raw: String): String =
        com.filestech.sms.core.ext.phoneIdentityKey(raw) { n ->
            val t = n.trim()
            val d = t.filter { it.isDigit() }
            when {
                t.startsWith('+') && d.length in 8..15 -> "+" + d
                d.length == 10 && d.startsWith("0") -> "+33" + d.drop(1)
                else -> null
            }
        }

    private fun entry(id: Long, raw: String, normalized: String, createdAt: Long = id) =
        LegacyBlockEntry(id = id, rawNumber = raw, normalizedNumber = normalized, createdAt = createdAt)

    @Test
    fun `une entree heritee en forme internationale est convertie`() {
        // Le cas qui, sans conversion, aurait désarmé le blocage : la clé enregistrée était le
        // numéro normalisé entier, que plus personne ne calcule.
        // v1.27.2 (audit Codex final, F-03) — la cle cible est desormais l'E.164, pas le suffixe
        // de neuf chiffres : c'est elle qui separe deux correspondants de pays differents.
        val plan = planBlocklistRekey(key = ::cleFr, entries = listOf(entry(1, "+33612345678", "612345678")))

        assertThat(plan).containsExactly(RekeyAction.Update(1, "+33612345678"))
    }

    @Test
    fun `une entree alphanumerique heritee sur cle vide est reconstituee depuis le libelle brut`() {
        // Avant la v1.25.3, `normalizePhone("SFR")` rendait la chaîne vide et toutes ces entrées
        // se confondaient. `raw_number` est le seul champ à avoir gardé de quoi les distinguer.
        val plan = planBlocklistRekey(key = ::cleFr, entries = listOf(entry(1, "SFR", "")))

        assertThat(plan).containsExactly(RekeyAction.Update(1, "sfr"))
    }

    @Test
    fun `un operateur alphanumerique et le code court homonyme ne sont pas fusionnes`() {
        // Le défaut de fond : « SFR 123 » se réduisait au code court « 123 ». Les deux entrées
        // doivent survivre séparément, sans qu'aucune ne soit prise pour un doublon de l'autre.
        val plan = planBlocklistRekey(
            key = ::cleFr,
            entries = listOf(
                entry(1, "SFR 123", "123"),
                entry(2, "123", "123"),
            ),
        )

        assertThat(plan).containsExactly(
            RekeyAction.Update(1, "sfr 123"),
            RekeyAction.Retain("123"),
        )
        assertThat(plan.filterIsInstance<RekeyAction.Collapse>()).isEmpty()
    }

    @Test
    fun `deux ecritures d'un meme numero fusionnent en gardant la plus ancienne`() {
        val plan = planBlocklistRekey(
            key = ::cleFr,
            entries = listOf(
                // Cles HERITEES (suffixe de neuf chiffres) : c'est l'etat reel d'une base
                // d'avant v1.27.2, que cette passe doit convertir.
                entry(2, "0612345678", "612345678", createdAt = 200),
                entry(1, "+33612345678", "612345678", createdAt = 100),
            ),
        )

        // National et international du MEME numero se rejoignent toujours — mais sur l'E.164,
        // qui porte le pays, et non plus sur un suffixe qui l'ignore.
        assertThat(plan).containsExactly(
            RekeyAction.Update(1, "+33612345678"),
            RekeyAction.Collapse(2, "+33612345678"),
        ).inOrder()
    }

    @Test
    fun `une suppression de doublon designe toujours la cle qui la remplace`() {
        // C'est ce lien qui permet à l'exécutant de refuser la suppression quand la réécriture
        // qui la justifie a échoué. Sans lui, un échec d'écriture deviendrait un déblocage.
        val plan = planBlocklistRekey(
            key = ::cleFr,
            entries = listOf(
                entry(1, "0612345678", "0612345678", createdAt = 100),
                entry(2, "+33 6 12 34 56 78", "+33612345678", createdAt = 200),
            ),
        )
        val collapse = plan.filterIsInstance<RekeyAction.Collapse>().single()
        val provided = plan.mapNotNull {
            when (it) {
                is RekeyAction.Retain -> it.key
                is RekeyAction.Update -> it.key
                is RekeyAction.Collapse -> null
            }
        }

        assertThat(provided).contains(collapse.supersededBy)
    }

    @Test
    fun `une entree deja correcte n'entraine aucune ecriture`() {
        val plan = planBlocklistRekey(
            key = ::cleFr,
            entries = listOf(
                entry(1, "0612345678", "+33612345678"),
                entry(2, "SFR", "sfr"),
            ),
        )

        assertThat(plan).containsExactly(RekeyAction.Retain("+33612345678"), RekeyAction.Retain("sfr"))
    }

    @Test
    fun `une entree dont la cle recalculee serait vide est laissee intacte`() {
        // Mieux vaut une entrée inerte qu'une entrée détruite : on ne sait pas faire mieux, on
        // ne casse rien.
        val plan = planBlocklistRekey(key = ::cleFr, entries = listOf(entry(1, "   ", "")))

        assertThat(plan).isEmpty()
    }

    @Test
    fun `rejouer la conversion sur une base deja migree ne produit que des conservations`() {
        // La passe tourne à chaque démarrage, sans drapeau de complétion : elle doit devenir
        // silencieuse dès le second passage.
        val migrated = listOf(
            entry(1, "+33612345678", "+33612345678"),
            entry(2, "SFR 123", "sfr 123"),
            entry(3, "123", "123"),
        )

        val plan = planBlocklistRekey(key = ::cleFr, entries = migrated)

        assertThat(plan).hasSize(3)
        assertThat(plan.filterIsInstance<RekeyAction.Retain>()).hasSize(3)
    }
}
