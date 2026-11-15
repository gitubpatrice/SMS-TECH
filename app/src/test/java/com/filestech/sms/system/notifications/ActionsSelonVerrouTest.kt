package com.filestech.sms.system.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
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
 * Trois défauts antérieurs, corrigés sur décision de Patrice : sous verrou, « Répondre » et « Renvoyer » étaient
 * proposés puis refusés sans un mot (une réponse tapée disparaissait) ; et les actions, figées à la pose, ne
 * suivaient pas un verrou activé ensuite. Puis la relecture du correctif (GPT, Claude) : Coffre, sourdine, ancienne
 * version, ligne de refus qui survit, reposes inutiles. Ces tests lisent les notifications RÉELLEMENT postées.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ActionsSelonVerrouTest {

    private val contexte: Context = ApplicationProvider.getApplicationContext()
    private val nm: NotificationManager = contexte.getSystemService(NotificationManager::class.java)
    private val reglages = mockk<SettingsRepository>()
    private val conversations = mockk<ConversationDao>()
    private val visibilite = mockk<CorrespondentVisibilityPolicy>()
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

    private fun conversation(auCoffre: Boolean = false, enSourdine: Boolean = false) = ConversationEntity(
        id = CONVERSATION,
        threadId = 1,
        addressesCsv = ADRESSE,
        displayName = "Alice",
        lastMessageAt = 0,
        lastMessagePreview = null,
        inVault = auCoffre,
        muted = enSourdine,
    )

    @Before
    fun preparer() {
        shadowOf(contexte as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationChannelInitializer(contexte).ensureDefaultChannels()
        coEvery { reglages.hydratedOrNull() } answers { appSettings() }
        coEvery { conversations.findById(CONVERSATION) } returns conversation()
        coEvery { visibilite.verdictPour(any(), any()) } returns CorrespondentVisibilityPolicy.Verdict.NOMMER
        coEvery { visibilite.ecranVerrouille() } returns CorrespondentVisibilityPolicy.EcranVerrouille.PRIVE
    }

    private fun messages() = IncomingMessageNotifier(
        context = contexte,
        settings = reglages,
        contacts = mockk(relaxed = true),
        activeConversationTracker = ActiveConversationTracker(),
        conversationDao = conversations,
        intentToken = mockk(relaxed = true),
        io = Dispatchers.Unconfined,
    )

    private fun echecs() = OutgoingFailureNotifier(contexte, visibilite, reglages)

    private fun posterMessage() = runBlocking { messages().notifyIncoming(ADRESSE, "Bonjour", MESSAGE, CONVERSATION) }

    private fun reappliquer(verrouApp: Boolean) = runBlocking { messages().reappliquerActions(verrouApp, true) }

    private fun message(): Notification? =
        shadowOf(nm).activeNotifications.singleOrNull { it.tag == conversationTagFor(CONVERSATION) }?.notification

    private fun echec(): Notification? = shadowOf(nm).activeNotifications.singleOrNull { it.tag == null }?.notification

    private fun Notification.libelles(): List<String> = actions.orEmpty().map { it.title.toString() }

    private fun Notification.reposee(): Boolean = flags and Notification.FLAG_ONLY_ALERT_ONCE != 0

    private fun Notification.ligne(): String? = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

    private val repondre get() = contexte.getString(R.string.notif_reply_label)
    private val marquerLu get() = contexte.getString(R.string.notif_mark_read_label)
    private val renvoyer get() = contexte.getString(R.string.action_retry)

    @Test
    fun `sans verrou, repondre et marquer comme lu`() {
        posterMessage()

        assertThat(message()!!.libelles()).containsExactly(repondre, marquerLu)
    }

    @Test
    fun `sous verrou, plus de repondre, et marquer comme lu exige le deverrouillage`() {
        verrou = LockMode.PIN
        posterMessage()

        assertThat(message()!!.libelles()).containsExactly(marquerLu)
        assertThat(message()!!.actions.single().isAuthenticationRequired).isTrue()
    }

    @Test
    fun `activer le verrou retire repondre d'une notification deja affichee, et le retirer le remet`() {
        posterMessage()
        val titre = message()!!.extras.getCharSequence(Notification.EXTRA_TITLE)

        reappliquer(verrouApp = true)
        assertThat(message()!!.libelles()).containsExactly(marquerLu)
        assertThat(message()!!.extras.getCharSequence(Notification.EXTRA_TITLE)).isEqualTo(titre)
        assertThat(message()!!.reposee()).isTrue()

        reappliquer(verrouApp = false)
        assertThat(message()!!.libelles()).containsExactly(repondre, marquerLu)
    }

    /** Android jette les mises à jour au-delà de quelques par seconde : on ne repose que ce qui change. */
    @Test
    fun `rien n'est repose quand rien ne change`() {
        posterMessage()

        reappliquer(verrouApp = false)

        assertThat(message()!!.reposee()).isFalse()
    }

    @Test
    fun `un refus est dit dans la notification, et la ligne s'efface ensuite`() {
        posterMessage()
        verrou = LockMode.PIN
        val idNotification = shadowOf(nm).activeNotifications.single().id

        runBlocking { messages().signalerRefus(CONVERSATION, idNotification, MotifRefus.REPONSE) }
        assertThat(message()!!.ligne()).isEqualTo(contexte.getString(R.string.notif_reply_refused_locked))
        assertThat(message()!!.libelles()).containsExactly(marquerLu)

        reappliquer(verrouApp = false)
        assertThat(message()!!.ligne()).isNull()
        assertThat(message()!!.libelles()).containsExactly(repondre, marquerLu)
    }

    /** Promesse du Coffre (« ni nom, ni aperçu ») : la notification d'une conversation passée au Coffre est retirée. */
    @Test
    fun `une conversation passee au Coffre perd sa notification`() {
        posterMessage()
        coEvery { conversations.findById(CONVERSATION) } returns conversation(auCoffre = true)

        reappliquer(verrouApp = true)

        assertThat(message()).isNull()
    }

    @Test
    fun `une conversation en sourdine n'est pas reposee`() {
        posterMessage()
        coEvery { conversations.findById(CONVERSATION) } returns conversation(enSourdine = true)

        reappliquer(verrouApp = true)

        assertThat(message()!!.libelles()).containsExactly(repondre, marquerLu)
        assertThat(message()!!.reposee()).isFalse()
    }

    /** Notification d'une version antérieure (sans identité) : sous verrou, elle perd « Répondre » et se durcit. */
    @Test
    fun `une notification d'une version anterieure est durcie sous verrou`() {
        val pi = PendingIntent.getBroadcast(contexte, 1, Intent("x"), PendingIntent.FLAG_IMMUTABLE)
        val ancienne = NotificationCompat.Builder(contexte, NotificationChannelInitializer.CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_notification_message)
            .setContentTitle("Alice")
            .addAction(
                NotificationCompat.Action.Builder(0, repondre, pi)
                    .addRemoteInput(RemoteInput.Builder(IncomingMessageNotifier.KEY_REPLY).build())
                    .build(),
            )
            .addAction(NotificationCompat.Action.Builder(0, marquerLu, pi).build())
            .build()
        nm.notify(conversationTagFor(CONVERSATION), 99, ancienne)

        reappliquer(verrouApp = true)

        assertThat(message()!!.libelles()).containsExactly(marquerLu)
        assertThat(message()!!.actions.single().isAuthenticationRequired).isTrue()
    }

    @Test
    fun `renvoyer suit le verrou, et son refus se dit`() {
        verrou = LockMode.PIN
        runBlocking { echecs().notifierEchec(MESSAGE, ADRESSE, CONVERSATION) }
        assertThat(echec()!!.libelles()).isEmpty()

        runBlocking { echecs().reappliquerActions(verrouApp = false) }
        assertThat(echec()!!.libelles()).containsExactly(renvoyer)

        verrou = LockMode.PIN
        val id = shadowOf(nm).activeNotifications.single { it.tag == null }.id
        runBlocking { echecs().signalerRefus(id) }
        assertThat(echec()!!.libelles()).isEmpty()
        assertThat(echec()!!.ligne()).isEqualTo(contexte.getString(R.string.send_failure_refused_locked))
    }

    /** Correspondant passé au Coffre, ou session leurre : le verdict est refait, la notification d'échec retirée. */
    @Test
    fun `un echec d'envoi devenu confidentiel est retire`() {
        runBlocking { echecs().notifierEchec(MESSAGE, ADRESSE, CONVERSATION) }
        coEvery { visibilite.verdictPour(any(), any()) } returns CorrespondentVisibilityPolicy.Verdict.TAIRE

        runBlocking { echecs().reappliquerActions(verrouApp = true) }

        assertThat(echec()).isNull()
    }

    @Test
    fun `regles pures`() {
        assertThat(reponseProposee(reponseActivee = true, verrouApp = false)).isTrue()
        assertThat(reponseProposee(reponseActivee = true, verrouApp = true)).isFalse()
        assertThat(reponseProposee(reponseActivee = false, verrouApp = false)).isFalse()
        assertThat(verrouAppActif(null)).isTrue()
        assertThat(IdentiteNotif.depuis(IdentiteNotif("+1", 2L, 3, 4L).versExtras()))
            .isEqualTo(IdentiteNotif("+1", 2L, 3, 4L))
        assertThat(IdentiteNotif.depuis(android.os.Bundle())).isNull()
        assertThat(conversationIdDuTag(conversationTagFor(123_456L))).isEqualTo(123_456L)
        assertThat(conversationIdDuTag("autre.7")).isNull()
        assertThat(conversationIdDuTag(null)).isNull()
    }

    private companion object {
        const val ADRESSE = "+33612345678"
        const val CONVERSATION = 7L
        const val MESSAGE = 42L
    }
}
