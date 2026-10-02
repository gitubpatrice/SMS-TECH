package com.filestech.sms.system.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.sms.domain.settings.NotificationStyle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * v1.28.13 — l'état qui commande les bannières « Notifications désactivées ».
 *
 * La vérification doit répondre `false` dans les deux cas où `notify()` ne poste rien sans erreur —
 * notifications de l'application coupées, ou canal de la fonction coupé — et `true` tant qu'un canal
 * n'est pas encore créé (étape en attente, pas une coupure). Chaque fonction a ses propres canaux, et
 * la bannière doit lire celui sur lequel la fonction poste RÉELLEMENT : ni plus (un canal voisin
 * coupé ne doit pas l'allumer), ni moins (relecture GPT, puis audit pré-release L2).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class NotificationsDesMessagesVisiblesTest {

    private val contexte: Context = ApplicationProvider.getApplicationContext()
    private val nm: NotificationManager = contexte.getSystemService(NotificationManager::class.java)

    private val canalMessages = canalDesMessages(NotificationStyle.HEADS_UP)

    @Test
    fun `notifications de l'application coupees - pas visibles`() {
        shadowOf(nm).setNotificationsEnabled(false)

        assertThat(canauxVisibles(contexte, canalMessages)).isFalse()
    }

    @Test
    fun `canal des messages coupe - pas visibles`() {
        nm.createNotificationChannel(canal(canalMessages, NotificationManager.IMPORTANCE_NONE))

        assertThat(canauxVisibles(contexte, canalMessages)).isFalse()
    }

    @Test
    fun `canal des messages actif - visibles`() {
        nm.createNotificationChannel(canal(canalMessages, NotificationManager.IMPORTANCE_HIGH))

        assertThat(canauxVisibles(contexte, canalMessages)).isTrue()
    }

    @Test
    fun `canal pas encore cree - visibles, ce n'est pas une coupure`() {
        assertThat(canauxVisibles(contexte, canalMessages)).isTrue()
    }

    /** Audit L2 : le style « Silencieux » poste sur son propre canal, c'est celui-là qu'on lit. */
    @Test
    fun `style silencieux - c'est le canal silencieux qui compte`() {
        val canalSilencieux = canalDesMessages(NotificationStyle.SILENT)
        assertThat(canalSilencieux).isEqualTo(NotificationChannelInitializer.CHANNEL_INCOMING_SILENT)

        nm.createNotificationChannel(canal(canalSilencieux, NotificationManager.IMPORTANCE_NONE))

        assertThat(canauxVisibles(contexte, canalSilencieux)).isFalse()
        assertThat(canauxVisibles(contexte, canalMessages)).isTrue()
    }

    @Test
    fun `canal d'avertissement du Safety call coupe - seul le Safety call est touche`() {
        nm.createNotificationChannel(canal(CANAL_DU_SAFETY_CALL, NotificationManager.IMPORTANCE_NONE))

        assertThat(canauxVisibles(contexte, CANAL_DU_SAFETY_CALL)).isFalse()
        assertThat(canauxVisibles(contexte, CANAL_DU_RACCOURCI_D_URGENCE)).isTrue()
        assertThat(canauxVisibles(contexte, canalMessages)).isTrue()
    }

    /**
     * Audit L2 : le reçu de fin n'empêche ni d'être prévenu ni d'arrêter la séquence. Le couper ne
     * doit pas faire afficher « le Safety call ne pourra ni vous prévenir ni vous laisser l'arrêter ».
     */
    @Test
    fun `seul le recu du Safety call coupe - pas de banniere`() {
        nm.createNotificationChannel(
            canal(NotificationChannelInitializer.CHANNEL_SAFETY_CALL_RECEIPT, NotificationManager.IMPORTANCE_NONE),
        )

        assertThat(canauxVisibles(contexte, CANAL_DU_SAFETY_CALL)).isTrue()
    }

    @Test
    fun `canal du raccourci d'urgence coupe - seul le raccourci est touche`() {
        nm.createNotificationChannel(canal(CANAL_DU_RACCOURCI_D_URGENCE, NotificationManager.IMPORTANCE_NONE))

        assertThat(canauxVisibles(contexte, CANAL_DU_RACCOURCI_D_URGENCE)).isFalse()
        assertThat(canauxVisibles(contexte, CANAL_DU_SAFETY_CALL)).isTrue()
    }

    private fun canal(id: String, importance: Int) = NotificationChannel(id, id, importance)
}
