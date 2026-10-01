package com.filestech.sms.system.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * v1.28.13 — l'état qui commande la bannière « Notifications désactivées » des Réglages.
 *
 * Écrite en v1.8.0 pour un avertissement rouge jamais branché : sans appelant, rien ne vérifiait
 * qu'elle disait vrai. Elle doit répondre `false` dans les deux cas où `notify()` ne poste rien
 * sans erreur — notifications de l'application coupées, canal des messages coupé — et `true` tant
 * que le canal n'est pas encore créé (étape en attente, pas une coupure).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class NotificationsDesMessagesVisiblesTest {

    private val contexte: Context = ApplicationProvider.getApplicationContext()
    private val nm: NotificationManager = contexte.getSystemService(NotificationManager::class.java)

    @Test
    fun `notifications de l'application coupees - pas visibles`() {
        shadowOf(nm).setNotificationsEnabled(false)

        assertThat(notificationsDesMessagesVisibles(contexte)).isFalse()
    }

    @Test
    fun `canal des messages coupe - pas visibles`() {
        nm.createNotificationChannel(canalDesMessages(NotificationManager.IMPORTANCE_NONE))

        assertThat(notificationsDesMessagesVisibles(contexte)).isFalse()
    }

    @Test
    fun `canal des messages actif - visibles`() {
        nm.createNotificationChannel(canalDesMessages(NotificationManager.IMPORTANCE_HIGH))

        assertThat(notificationsDesMessagesVisibles(contexte)).isTrue()
    }

    private fun canalDesMessages(importance: Int) =
        NotificationChannel(NotificationChannelInitializer.CHANNEL_INCOMING, "messages", importance)

    @Test
    fun `canal pas encore cree - visibles, ce n'est pas une coupure`() {
        assertThat(notificationsDesMessagesVisibles(contexte)).isTrue()
    }

    /**
     * Relecture GPT du correctif : le Safety call et le raccourci d'urgence ont leurs PROPRES canaux.
     * Couper l'un d'eux dans Android laisse l'interrupteur global allumé ; la bannière qui ne
     * regardait que lui restait muette. Chaque coupure doit éteindre SA fonction, et seulement elle.
     */
    @Test
    fun `canal d'avertissement du Safety call coupe - seul le Safety call est touche`() {
        nm.createNotificationChannel(canal(NotificationChannelInitializer.CHANNEL_SAFETY_CALL_WARNING))

        assertThat(notificationsDuSafetyCallVisibles(contexte)).isFalse()
        assertThat(raccourciDUrgenceVisible(contexte)).isTrue()
        assertThat(notificationsDesMessagesVisibles(contexte)).isTrue()
    }

    @Test
    fun `canal d'accuse de reception du Safety call coupe - Safety call pas visible`() {
        nm.createNotificationChannel(canal(NotificationChannelInitializer.CHANNEL_SAFETY_CALL_RECEIPT))

        assertThat(notificationsDuSafetyCallVisibles(contexte)).isFalse()
    }

    @Test
    fun `canal du raccourci d'urgence coupe - seul le raccourci est touche`() {
        nm.createNotificationChannel(canal(NotificationChannelInitializer.CHANNEL_EMERGENCY_SHORTCUT))

        assertThat(raccourciDUrgenceVisible(contexte)).isFalse()
        assertThat(notificationsDuSafetyCallVisibles(contexte)).isTrue()
    }

    /** Un canal coupé par l'utilisateur. */
    private fun canal(id: String) = NotificationChannel(id, id, NotificationManager.IMPORTANCE_NONE)
}
