package com.filestech.sms.ui.screens.thread

import com.filestech.sms.R
import com.filestech.sms.domain.model.Message
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** 2026-11-12 (issue #87) — quand la ligne « heure • état » s'affiche d'office, et ce que fait le toucher. */
class LigneHeureEtatTest {

    private fun message(
        id: Long,
        date: Long,
        direction: Message.Direction = Message.Direction.OUTGOING,
        status: Message.Status = Message.Status.DELIVERED,
    ) = Message(
        id = id,
        conversationId = 1L,
        address = "+33600000000",
        body = "Test $id",
        type = Message.Type.SMS,
        direction = direction,
        date = date,
        dateSent = null,
        read = true,
        starred = false,
        status = status,
        errorCode = null,
        attachmentsCount = 0,
        subId = null,
        scheduledAt = null,
    )

    /** Pour chaque message de [fil], dans l'ordre : sa ligne s'affiche-t-elle d'office ? */
    private fun dOffice(fil: List<Message>): List<Boolean> =
        fil.indices.map { i -> ligneHeureDOffice(fil[i], fil.getOrNull(i - 1), fil.getOrNull(i + 1)) }

    @Test
    fun `la capture de l'issue 87 - trois envois d'affilee montrent l'heure en tete ET l'etat sous le dernier`() {
        // Un message de la veille, puis six envois dans la même minute (les doublons de la capture compris).
        val fil = listOf(message(1, date = 0L, direction = Message.Direction.INCOMING)) +
            (2L..7L).map { message(it, date = DIX_H_44 + it * 5_000L) }

        assertThat(dOffice(fil)).containsExactly(true, true, false, false, false, false, true).inOrder()
    }

    @Test
    fun `une reponse recue ferme la serie - l'etat s'affiche sous le dernier envoi avant elle`() {
        val fil = listOf(
            message(1, date = DIX_H_44),
            message(2, date = DIX_H_44 + 10_000L),
            message(3, date = DIX_H_44 + 20_000L, direction = Message.Direction.INCOMING),
            message(4, date = DIX_H_44 + 30_000L),
        )

        assertThat(dOffice(fil)).containsExactly(true, true, false, true).inOrder()
    }

    @Test
    fun `la serie suit la regle de la rafale - 5 minutes pile la prolongent, une de plus la ferme`() {
        val prolongee = listOf(message(1, date = DIX_H_44), message(2, date = DIX_H_44 + RAFALE_HEURE_MS))
        val fermee = listOf(message(1, date = DIX_H_44), message(2, date = DIX_H_44 + RAFALE_HEURE_MS + 1L))

        assertThat(fermeUneSerieEnvoyee(prolongee[0], prolongee[1])).isFalse()
        assertThat(fermeUneSerieEnvoyee(fermee[0], fermee[1])).isTrue()
        // La tête de rafale, inchangée : plus de 5 minutes après le précédent.
        assertThat(ouvreUneRafale(prolongee[1], prolongee[0])).isFalse()
        assertThat(ouvreUneRafale(fermee[1], fermee[0])).isTrue()
    }

    @Test
    fun `minuit coupe la serie et ouvre une rafale, comme le separateur de jour`() {
        // Deux minutes d'écart, de part et d'autre de minuit dans le fuseau du téléphone (celui des séparateurs).
        val veille = heureLocale(jour = 11, heure = 23, minute = 59)
        val lendemain = veille + 2 * 60_000L
        val avant = message(1, date = veille)
        val apres = message(2, date = lendemain)

        assertThat(fermeUneSerieEnvoyee(avant, apres)).isTrue()
        assertThat(ouvreUneRafale(apres, avant)).isTrue()
    }

    @Test
    fun `le temoin de minuit - le meme ecart dans la meme journee ne coupe rien`() {
        val soir = heureLocale(jour = 11, heure = 23, minute = 55)
        val avant = message(1, date = soir)
        val apres = message(2, date = soir + 2 * 60_000L)

        assertThat(fermeUneSerieEnvoyee(avant, apres)).isFalse()
        assertThat(ouvreUneRafale(apres, avant)).isFalse()
    }

    @Test
    fun `l'heure revelee - se bascule, par message, et seuls les messages reveles sont gardes`() {
        val heures = HeuresRevelees()

        heures.basculer(1L)
        heures.basculer(2L)
        assertThat(heures.estRevelee(1L)).isTrue()
        assertThat(heures.estRevelee(2L)).isTrue()
        assertThat(heures.estRevelee(3L)).isFalse()

        heures.basculer(1L)
        assertThat(heures.estRevelee(1L)).isFalse()
        assertThat(heures.estRevelee(2L)).isTrue()
        // Masquer retire l'entrée : la carte ne contient que les messages révélés.
        assertThat(heures.nombre).isEqualTo(1)
    }

    @Test
    fun `un message recu ne ferme jamais une serie d'envois`() {
        val recu = message(1, date = DIX_H_44, direction = Message.Direction.INCOMING)

        assertThat(fermeUneSerieEnvoyee(recu, next = null)).isFalse()
    }

    @Test
    fun `un echec s'affiche toujours, meme au milieu d'une serie`() {
        val fil = listOf(
            message(1, date = DIX_H_44),
            message(2, date = DIX_H_44 + 10_000L, status = Message.Status.FAILED),
            message(3, date = DIX_H_44 + 20_000L),
        )

        assertThat(dOffice(fil)).containsExactly(true, true, true).inOrder()
    }

    @Test
    fun `l'action - rien quand la ligne est d'office, sinon afficher puis masquer`() {
        assertThat(actionHeure(dOffice = true, revelee = false) {}).isNull()
        assertThat(actionHeure(dOffice = false, revelee = false) {}?.libelle).isEqualTo(R.string.action_show_time)
        assertThat(actionHeure(dOffice = false, revelee = true) {}?.libelle).isEqualTo(R.string.action_hide_time)
    }

    @Test
    fun `le toucher - un echec relance et ne bascule pas l'heure`() {
        var relances = 0
        var bascules = 0
        val action = actionHeure(dOffice = false, revelee = false) { bascules++ }

        surToucherBulle(message(1, date = 0L, status = Message.Status.FAILED), action) { relances++ }

        assertThat(relances).isEqualTo(1)
        assertThat(bascules).isEqualTo(0)
    }

    @Test
    fun `le toucher - hors echec il bascule l'heure et ne relance jamais`() {
        var relances = 0
        var bascules = 0
        val action = actionHeure(dOffice = false, revelee = false) { bascules++ }

        surToucherBulle(message(1, date = 0L, status = Message.Status.SENT), action) { relances++ }
        // Ligne d'office : pas d'action, et toujours pas de relance.
        surToucherBulle(message(2, date = 0L, status = Message.Status.DELIVERED), null) { relances++ }

        assertThat(bascules).isEqualTo(1)
        assertThat(relances).isEqualTo(0)
    }

    /** Novembre 2026, à l'heure LOCALE donnée : le fuseau de la machine de test, comme celui du téléphone. */
    private fun heureLocale(jour: Int, heure: Int, minute: Int): Long = java.util.Calendar.getInstance().apply {
        clear()
        set(2026, java.util.Calendar.NOVEMBER, jour, heure, minute, 0)
    }.timeInMillis

    private companion object {
        /** 2026-11-11T10:44:00Z, l'heure de la capture de l'issue #87. */
        const val DIX_H_44 = 1_794_393_840_000L
    }
}
