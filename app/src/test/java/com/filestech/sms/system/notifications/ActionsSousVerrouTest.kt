package com.filestech.sms.system.notifications

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-11-15 (v1.30.1, courriel d'Andrew) — sous le verrou de l'app, seules les actions qui ENVOIENT
 * un message sont refusées. « Marquer comme lu » était refusé lui aussi, sans un mot : avec le verrou
 * actif, il ne faisait rien (mesuré sur émulateur API 34).
 */
class ActionsSousVerrouTest {

    @Test
    fun `marquer comme lu passe sous verrou`() {
        assertThat(NotificationActionReceiver.refuseeSousVerrou(NotificationActionReceiver.ACTION_MARK_READ))
            .isFalse()
    }

    @Test
    fun `repondre et renvoyer restent refuses sous verrou`() {
        assertThat(NotificationActionReceiver.refuseeSousVerrou(NotificationActionReceiver.ACTION_REPLY)).isTrue()
        assertThat(NotificationActionReceiver.refuseeSousVerrou(NotificationActionReceiver.ACTION_RETRY_SEND))
            .isTrue()
    }

    @Test
    fun `une action inconnue ou absente est refusee sous verrou`() {
        assertThat(NotificationActionReceiver.refuseeSousVerrou("com.filestech.sms.action.AUTRE")).isTrue()
        assertThat(NotificationActionReceiver.refuseeSousVerrou(null)).isTrue()
    }
}
