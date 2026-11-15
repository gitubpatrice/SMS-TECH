package com.filestech.sms.system.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.sms.R
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.LockMode
import com.filestech.sms.domain.settings.NotificationSettings
import com.filestech.sms.domain.settings.NotificationStyle
import com.filestech.sms.domain.settings.PreviewMode
import com.filestech.sms.domain.settings.SecuritySettings
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 2026-11-15 (v1.30.1) — **les actions des notifications suivent le verrou de l'app, et un refus se dit.**
 *
 * Trois défauts antérieurs, relevés par la relecture du jour et corrigés sur décision de Patrice : sous verrou,
 * « Répondre » et « Renvoyer » étaient proposés puis refusés sans un mot (une réponse tapée disparaissait) ; et
 * les actions, figées à la pose, ne suivaient pas un verrou activé ensuite. Ces tests lisent les notifications
 * RÉELLEMENT postées, puis reposées.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ActionsSelonVerrouTest {

    private val contexte: Context = ApplicationProvider.getApplicationContext()
    private val nm: NotificationManager = contexte.getSystemService(NotificationManager::class.java)
    private val reglages = mockk<SettingsRepository>()
    private val conversations = mockk<ConversationDao>()
    private var verrou = LockMode.OFF

    private fun appSettings() = AppSettings(
        notifications = NotificationSettings(
            enabled = true,
            style = NotificationStyle.HEADS_UP,
            previewMode = PreviewMode.ALWAYS,
            inlineReply = true,
        ),
        security = SecuritySettings(lockMode = verrou),
    )

    @Before
    fun preparer() {
        shadowOf(contexte as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationChannelInitializer(contexte).ensureDefaultChannels()
        coEvery { reglages.hydratedOrNull() } answers { appSettings() }
        coEvery { conversations.findById(CONVERSATION) } returns ConversationEntity(
            id = CONVERSATION,
            threadId = 1,
            addressesCsv = ADRESSE,
            displayName = "Alice",
            lastMessageAt = 0,
            lastMessagePreview = null,
        )
    }

    private fun notificateurMessages() = IncomingMessageNotifier(
        context = contexte,
        settings = reglages,
        contacts = mockk(relaxed = true),
        activeConversationTracker = ActiveConversationTracker(),
        conversationDao = conversations,
        intentToken = mockk(relaxed = true),
        io = Dispatchers.Unconfined,
    )

    private fun notificateurEchecs(): OutgoingFailureNotifier {
        val visibilite = mockk<CorrespondentVisibilityPolicy>()
        coEvery { visibilite.verdictPour(any(), any()) } returns CorrespondentVisibilityPolicy.Verdict.NOMMER
        coEvery { visibilite.ecranVerrouille() } returns CorrespondentVisibilityPolicy.EcranVerrouille.PRIVE
        return OutgoingFailureNotifier(contexte, visibilite, reglages)
    }

    private fun posterMessage() = runBlocking {
        notificateurMessages().notifyIncoming(ADRESSE, "Bonjour", MESSAGE, CONVERSATION)
    }

    private fun messageAffiche(): Notification =
        shadowOf(nm).activeNotifications.single { it.tag == conversationTagFor(CONVERSATION) }.notification

    private fun echecAffiche(): Notification = shadowOf(nm).activeNotifications.single { it.tag == null }.notification

    private fun Notification.libelles(): List<String> = actions.orEmpty().map { it.title.toString() }

    private val repondre get() = contexte.getString(R.string.notif_reply_label)
    private val marquerLu get() = contexte.getString(R.string.notif_mark_read_label)
    private val renvoyer get() = contexte.getString(R.string.action_retry)

    @Test
    fun `sans verrou, repondre et marquer comme lu`() {
        posterMessage()

        assertThat(messageAffiche().libelles()).containsExactly(repondre, marquerLu)
    }

    @Test
    fun `sous verrou, plus de repondre`() {
        verrou = LockMode.PIN
        posterMessage()

        assertThat(messageAffiche().libelles()).containsExactly(marquerLu)
    }

    @Test
    fun `activer le verrou retire repondre d'une notification deja affichee, et le retirer le remet`() {
        posterMessage()
        val titre = messageAffiche().extras.getCharSequence(Notification.EXTRA_TITLE)

        verrou = LockMode.PIN
        runBlocking { notificateurMessages().reappliquerActions() }
        assertThat(messageAffiche().libelles()).containsExactly(marquerLu)
        // Reposée à l'identique hormis les actions : même titre, sans second son.
        assertThat(messageAffiche().extras.getCharSequence(Notification.EXTRA_TITLE)).isEqualTo(titre)
        assertThat(messageAffiche().flags and Notification.FLAG_ONLY_ALERT_ONCE).isNotEqualTo(0)

        verrou = LockMode.OFF
        runBlocking { notificateurMessages().reappliquerActions() }
        assertThat(messageAffiche().libelles()).containsExactly(repondre, marquerLu)
    }

    @Test
    fun `une reponse refusee sous verrou est dite dans la notification`() {
        posterMessage()
        verrou = LockMode.PIN
        val idNotification = shadowOf(nm).activeNotifications.single().id

        runBlocking { notificateurMessages().signalerRefus(CONVERSATION, idNotification, MotifRefus.REPONSE) }

        assertThat(messageAffiche().extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
            .isEqualTo(contexte.getString(R.string.notif_reply_refused_locked))
        assertThat(messageAffiche().libelles()).containsExactly(marquerLu)
    }

    @Test
    fun `une conversation passee au Coffre n'est pas reposee`() {
        posterMessage()
        coEvery { conversations.findById(CONVERSATION) } returns ConversationEntity(
            id = CONVERSATION,
            threadId = 1,
            addressesCsv = ADRESSE,
            displayName = "Alice",
            lastMessageAt = 0,
            lastMessagePreview = null,
            inVault = true,
        )
        verrou = LockMode.PIN

        runBlocking { notificateurMessages().reappliquerActions() }

        // Rien de reposé : les actions d'origine restent, aucune mise à jour n'a écrit la notification.
        assertThat(messageAffiche().libelles()).containsExactly(repondre, marquerLu)
    }

    @Test
    fun `renvoyer suit le verrou, et son refus se dit`() {
        verrou = LockMode.PIN
        runBlocking { notificateurEchecs().notifierEchec(MESSAGE, ADRESSE, CONVERSATION) }
        assertThat(echecAffiche().libelles()).isEmpty()

        verrou = LockMode.OFF
        runBlocking { notificateurEchecs().reappliquerActions() }
        assertThat(echecAffiche().libelles()).containsExactly(renvoyer)

        verrou = LockMode.PIN
        val id = shadowOf(nm).activeNotifications.single { it.tag == null }.id
        runBlocking { notificateurEchecs().signalerRefus(id) }
        assertThat(echecAffiche().libelles()).isEmpty()
        assertThat(echecAffiche().extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
            .isEqualTo(contexte.getString(R.string.send_failure_refused_locked))
    }

    @Test
    fun `regles pures - reponse proposee et verrou illisible`() {
        assertThat(reponseProposee(reponseActivee = true, verrouApp = false)).isTrue()
        assertThat(reponseProposee(reponseActivee = true, verrouApp = true)).isFalse()
        assertThat(reponseProposee(reponseActivee = false, verrouApp = false)).isFalse()
        assertThat(verrouAppActif(null)).isTrue()
        assertThat(IdentiteNotif.depuis(IdentiteNotif("+1", 2L, 3, 4L).versExtras()))
            .isEqualTo(IdentiteNotif("+1", 2L, 3, 4L))
        assertThat(IdentiteNotif.depuis(android.os.Bundle())).isNull()
    }

    private companion object {
        const val ADRESSE = "+33612345678"
        const val CONVERSATION = 7L
        const val MESSAGE = 42L
    }
}
