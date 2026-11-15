package com.filestech.sms.system.notifications

import com.filestech.sms.system.notifications.NotificationActionReceiver.Companion.ACTION_MARK_READ
import com.filestech.sms.system.notifications.NotificationActionReceiver.Companion.ACTION_REPLY
import com.filestech.sms.system.notifications.NotificationActionReceiver.Companion.ACTION_RETRY_SEND
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.system.notifications.NotificationActionReceiver.Companion.conversationAMarquerLue
import com.filestech.sms.system.notifications.NotificationActionReceiver.Companion.refuseeSousVerrou
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-11-15 (v1.30.1, courriel d'Andrew) — sous le verrou de l'app, seules les actions qui ENVOIENT un
 * message sont refusées... tant que le téléphone est déverrouillé. « Marquer comme lu » était refusé sans
 * un mot dès que le verrou de l'app était actif (mesuré sur émulateur API 34) ; depuis l'écran de
 * verrouillage du TÉLÉPHONE, il le reste (relecture Claude du 2026-11-15).
 */
class ActionsSousVerrouTest {

    @Test
    fun `marquer comme lu passe sous verrou de l'app, telephone deverrouille`() {
        assertThat(refuseeSousVerrou(ACTION_MARK_READ, appareilVerrouille = false)).isFalse()
    }

    @Test
    fun `marquer comme lu est refuse depuis l'ecran de verrouillage du telephone`() {
        assertThat(refuseeSousVerrou(ACTION_MARK_READ, appareilVerrouille = true)).isTrue()
    }

    @Test
    fun `repondre et renvoyer restent refuses sous verrou, telephone deverrouille ou non`() {
        for (appareilVerrouille in listOf(false, true)) {
            assertThat(refuseeSousVerrou(ACTION_REPLY, appareilVerrouille)).isTrue()
            assertThat(refuseeSousVerrou(ACTION_RETRY_SEND, appareilVerrouille)).isTrue()
        }
    }

    @Test
    fun `marquer comme lu vise la conversation designee, jamais une conversation du Coffre`() {
        assertThat(conversationAMarquerLue(conversation(id = 7L, auCoffre = false))).isEqualTo(7L)
        assertThat(conversationAMarquerLue(conversation(id = 7L, auCoffre = true))).isNull()
        assertThat(conversationAMarquerLue(null)).isNull()
    }

    private fun conversation(id: Long, auCoffre: Boolean) = Conversation(
        id = id,
        threadId = null,
        addresses = listOf(PhoneAddress.of("+33611111111")),
        displayName = null,
        lastMessageAt = 0L,
        lastMessagePreview = null,
        unreadCount = 1,
        pinned = false,
        archived = false,
        muted = false,
        inVault = auCoffre,
        draft = null,
    )

    @Test
    fun `une action inconnue ou absente est refusee sous verrou`() {
        assertThat(refuseeSousVerrou("com.filestech.sms.action.AUTRE", appareilVerrouille = false)).isTrue()
        assertThat(refuseeSousVerrou(null, appareilVerrouille = false)).isTrue()
    }
}
