package com.filestech.sms.system.notifications

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-11-15 (v1.30.1, issue #60, second signalement) — l'état « conversation à l'écran » appartient
 * à l'ÉCRAN qui l'a posé.
 *
 * Une transition de navigation démarre l'écran entrant avant d'arrêter le sortant. Quand les deux
 * montrent la MÊME conversation, l'ancien `clearActive(conversationId)` effaçait l'état que
 * l'entrant venait de poser : la conversation restait affichée, et la notification de ses nouveaux
 * messages restait dans le volet au lieu de s'effacer. Le premier test reproduit exactement cet
 * ordre.
 */
class ActiveConversationTrackerTest {

    private val tracker = ActiveConversationTracker()
    private val ecranSortant = Any()
    private val ecranEntrant = Any()

    @Test
    fun `l'arret de l'ecran sortant n'efface pas l'ecran entrant de la meme conversation`() {
        tracker.setActive(CONVERSATION_A, ecranSortant)
        // Transition : l'entrant démarre (ON_START) AVANT que le sortant ne s'arrête (ON_STOP).
        tracker.setActive(CONVERSATION_A, ecranEntrant)
        tracker.clearActive(ecranSortant)

        assertThat(tracker.isActive(CONVERSATION_A)).isTrue()
    }

    @Test
    fun `l'arret de l'ecran sortant n'efface pas l'ecran entrant d'une autre conversation`() {
        tracker.setActive(CONVERSATION_A, ecranSortant)
        tracker.setActive(CONVERSATION_B, ecranEntrant)
        tracker.clearActive(ecranSortant)

        assertThat(tracker.isActive(CONVERSATION_B)).isTrue()
        assertThat(tracker.isActive(CONVERSATION_A)).isFalse()
    }

    @Test
    fun `l'ecran qui a pose l'etat l'efface a son arret`() {
        tracker.setActive(CONVERSATION_A, ecranEntrant)
        assertThat(tracker.isActive(CONVERSATION_A)).isTrue()

        tracker.clearActive(ecranEntrant)

        assertThat(tracker.isActive(CONVERSATION_A)).isFalse()
    }

    @Test
    fun `aucune conversation n'est active au depart, et un arret sans etat ne fait rien`() {
        assertThat(tracker.isActive(CONVERSATION_A)).isFalse()

        tracker.clearActive(ecranSortant)

        assertThat(tracker.isActive(CONVERSATION_A)).isFalse()
    }

    @Test
    fun `seule la conversation de l'ecran visible est active`() {
        tracker.setActive(CONVERSATION_A, ecranEntrant)

        assertThat(tracker.isActive(CONVERSATION_A)).isTrue()
        assertThat(tracker.isActive(CONVERSATION_B)).isFalse()
    }

    private companion object {
        const val CONVERSATION_A = 7L
        const val CONVERSATION_B = 8L
    }
}
