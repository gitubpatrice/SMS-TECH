package com.filestech.sms.data.local.datastore

import com.filestech.sms.domain.safetycall.SafetyCallConfig
import com.filestech.sms.domain.safetycall.SafetyCallTriggerRecord
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.27.3 — verrouille la sérialisation de l'historique des déclenchements.
 *
 * # Ce qui compte ici
 *
 * Ce codec lit un fichier DataStore qui peut avoir été restauré depuis une sauvegarde tierce,
 * tronqué par un arrêt brutal, ou édité à la main. Il ne doit **jamais lever** : perdre une ligne
 * d'historique est bénin, mais une exception ici ferait échouer la lecture de **tous** les réglages
 * du même fichier — y compris la liste des contacts d'urgence.
 *
 * L'autre risque est le décalage de champs : un nom de contact contenant un séparateur casserait
 * l'entrée **et toutes les suivantes**. Il est donc strippé à l'encodage, et ce test le verrouille.
 */
class SafetyCallHistoryCodecTest {

    private companion object {
        const val AT = 1_785_966_836_000L
    }

    private fun record(
        triggeredAt: Long = AT,
        delivered: Int = 4,
        total: Int = 4,
        recipients: List<String> = listOf("Maman", "Papa"),
    ) = SafetyCallTriggerRecord(triggeredAt, delivered, total, recipients)

    @Test
    fun `aller-retour preserve les quatre champs`() {
        val decoded = SafetyCallHistoryCodec.decode(SafetyCallHistoryCodec.encode(listOf(record())))
        assertThat(decoded).containsExactly(record())
    }

    @Test
    fun `aller-retour preserve l ordre de plusieurs entrees`() {
        val history = listOf(
            record(triggeredAt = AT, delivered = 2),
            record(triggeredAt = AT + 1_000L, delivered = 4),
        )
        assertThat(SafetyCallHistoryCodec.decode(SafetyCallHistoryCodec.encode(history)))
            .isEqualTo(history)
    }

    @Test
    fun `une entree sans destinataire lisible reste valide`() {
        val orphan = record(recipients = emptyList())
        val decoded = SafetyCallHistoryCodec.decode(SafetyCallHistoryCodec.encode(listOf(orphan)))
        assertThat(decoded).containsExactly(orphan)
    }

    /**
     * 🔴 Le décalage de champs : sans strip, un nom contenant `|` ou `;` casserait l'entrée et
     * toutes celles qui suivent sur la même lecture.
     */
    @Test
    fun `les separateurs sont strippes des libelles`() {
        val piege = record(recipients = listOf("Ma|man", "Pa;pa"))
        val decoded = SafetyCallHistoryCodec.decode(SafetyCallHistoryCodec.encode(listOf(piege)))
        assertThat(decoded.single().recipients).containsExactly("Maman", "Papa")
    }

    /** Un retour à la ligne dans un nom couperait l'entrée en deux. */
    @Test
    fun `un retour a la ligne dans un libelle ne casse pas l entree`() {
        val piege = record(recipients = listOf("Ma\nman"))
        val decoded = SafetyCallHistoryCodec.decode(SafetyCallHistoryCodec.encode(listOf(piege)))
        assertThat(decoded).hasSize(1)
        assertThat(decoded.single().recipients).containsExactly("Maman")
    }

    @Test
    fun `une entree vide ou absente rend une liste vide`() {
        assertThat(SafetyCallHistoryCodec.decode(null)).isEmpty()
        assertThat(SafetyCallHistoryCodec.decode("")).isEmpty()
        assertThat(SafetyCallHistoryCodec.decode("   ")).isEmpty()
    }

    /** Tolérance : les lignes illisibles sont ignorées, les valides du même fichier conservées. */
    @Test
    fun `les lignes mal formees sont ignorees sans faire perdre les autres`() {
        val raw = listOf(
            "pas du tout une entree",
            "$AT|4|4|Maman",
            "$AT|pasunnombre|4|Papa",
            "$AT|4",
            "0|4|4|Zero",
            "${AT + 1}|4|0|TotalNul",
            "${AT + 2}|0|4|AucunEnvoi",
        ).joinToString("\n")

        val decoded = SafetyCallHistoryCodec.decode(raw)

        assertThat(decoded).hasSize(1)
        assertThat(decoded.single().triggeredAt).isEqualTo(AT)
        assertThat(decoded.single().recipients).containsExactly("Maman")
    }

    /**
     * Un compte supérieur au total afficherait « 9 sur 4 ». On borne plutôt que de rejeter :
     * l'information « ça s'est déclenché ce jour-là » reste vraie et vaut mieux que rien.
     */
    @Test
    fun `un compte superieur au total est borne`() {
        val decoded = SafetyCallHistoryCodec.decode("$AT|9|4|Maman")
        assertThat(decoded.single().messagesDelivered).isEqualTo(4)
        assertThat(decoded.single().isComplete).isTrue()
    }

    /** La borne est appliquée des deux côtés : un fichier gonflé ne revient pas en mémoire entier. */
    @Test
    fun `la borne s applique a l encodage et au decodage`() {
        val trop = (0 until SafetyCallConfig.MAX_HISTORY + 5).map { record(triggeredAt = AT + it) }

        assertThat(SafetyCallHistoryCodec.encode(trop).lineSequence().count())
            .isEqualTo(SafetyCallConfig.MAX_HISTORY)

        val rawTrop = trop.joinToString("\n") { "${it.triggeredAt}|4|4|Maman" }
        val decoded = SafetyCallHistoryCodec.decode(rawTrop)
        assertThat(decoded).hasSize(SafetyCallConfig.MAX_HISTORY)
        // Ce sont les PLUS RÉCENTES qui survivent, comme à l'archivage.
        assertThat(decoded.last().triggeredAt)
            .isEqualTo(AT + SafetyCallConfig.MAX_HISTORY + 4)
    }
}
