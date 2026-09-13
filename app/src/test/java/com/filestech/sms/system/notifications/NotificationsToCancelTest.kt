package com.filestech.sms.system.notifications

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.28.7 — la decision d'annuler un LOT de notifications, testee sans `NotificationManager`.
 *
 * La purge de retention et la reconciliation de synchronisation suppriment des messages en
 * masse ; elles laissaient leurs notifications dans le volet. [notificationsToCancel] choisit,
 * parmi les notifications actives, celles qui portent un message supprime.
 */
class NotificationsToCancelTest {

    private fun active(conversationId: Long, messageId: Long) = conversationTagFor(conversationId) to notificationIdFor(messageId)

    @Test
    fun `la notification d'un message supprime est annulee, celle d'un message conserve non`() {
        val actives = listOf(active(1L, 10L), active(1L, 11L))

        val resultat = notificationsToCancel(actives, mapOf(1L to listOf(10L)))

        assertThat(resultat).containsExactly(active(1L, 10L))
    }

    /**
     * L'identifiant d'une notification est un HACHAGE du message : seul, il ne designe rien.
     * Le meme message ne peut pas vivre dans deux conversations, mais le meme identifiant, si —
     * c'est la paire tag + identifiant qui fait foi.
     */
    @Test
    fun `le meme identifiant dans une autre conversation n'est pas annule`() {
        val actives = listOf(active(1L, 10L), active(2L, 10L))

        val resultat = notificationsToCancel(actives, mapOf(1L to listOf(10L)))

        assertThat(resultat).containsExactly(active(1L, 10L))
    }

    @Test
    fun `une notification sans tag ou d'un autre composant n'est jamais concernee`() {
        val raccourci = null to notificationIdFor(10L)
        val autre = "com.filestech.sms.safety" to notificationIdFor(10L)

        val resultat = notificationsToCancel(listOf(raccourci, autre), mapOf(1L to listOf(10L)))

        assertThat(resultat).isEmpty()
    }

    @Test
    fun `plusieurs conversations dans un meme lot`() {
        val actives = listOf(active(1L, 10L), active(2L, 20L), active(3L, 30L))

        val resultat = notificationsToCancel(actives, mapOf(1L to listOf(10L), 2L to setOf(20L)))

        assertThat(resultat).containsExactly(active(1L, 10L), active(2L, 20L))
    }
}
