package com.filestech.sms.system.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.sms.R
import com.filestech.sms.system.receiver.RelanceMms
import com.filestech.sms.system.receiver.RelanceMmsReceiver
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 2026-11-06 — la notification « MMS non reçu » telle qu'elle est réellement postée.
 *
 * Courriel d'une utilisatrice : « too large… Tap to open MMS settings », et aucun réglage MMS dans
 * l'application. Ce test lit la notification POSTÉE — texte, intent du tap — et non une fonction voisine
 * qu'on croirait câblée : un texte qui promettrait de nouveau un réglage, ou un tap qui ne mènerait plus
 * à la fenêtre d'explication, le ferait échouer.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class MmsFailureNotifierTest {

    private val contexte: Context = ApplicationProvider.getApplicationContext()
    private val nm: NotificationManager = contexte.getSystemService(NotificationManager::class.java)
    private val visibilite = mockk<CorrespondentVisibilityPolicy>()

    @Before
    fun autoriser() {
        shadowOf(contexte as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        coEvery { visibilite.verdictPour(any(), any()) } returns CorrespondentVisibilityPolicy.Verdict.NOMMER
        coEvery { visibilite.ecranVerrouille() } returns CorrespondentVisibilityPolicy.EcranVerrouille.PRIVE
    }

    private fun poste(
        reason: MmsFailureNotifier.Reason,
        expediteur: String?,
        taille: Long? = null,
        codeAndroid: Int? = null,
        codeHttp: Int? = null,
    ): Notification {
        runBlocking {
            MmsFailureNotifier(contexte, visibilite).notifyFailure(reason, expediteur, taille, codeAndroid, codeHttp)
        }
        return shadowOf(nm).allNotifications.single()
    }

    private fun Notification.texte(): String = extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    @Test
    fun `trop volumineux - le texte ne promet plus de reglage, et donne la taille`() {
        val texte = poste(MmsFailureNotifier.Reason.TOO_LARGE, "+15551234567", 2_000_000L).texte()

        assertThat(texte).contains("1953 KB")
        assertThat(texte.lowercase()).doesNotContain("settings")
    }

    @Test
    fun `echec de telechargement - le texte ne promet plus de nouvel essai`() {
        val texte = poste(MmsFailureNotifier.Reason.DOWNLOAD_FAILED, "+15551234567").texte()

        assertThat(texte.lowercase()).doesNotContain("retry")
        assertThat(texte).contains("+15551234567")
    }

    /** Relecture du 2026-11-06 : le message EST arrivé, c'est un fichier qui n'a pas été enregistré. */
    @Test
    fun `fichier non enregistre - le texte ne dit pas que le message n'a pas ete telecharge`() {
        val texte = poste(MmsFailureNotifier.Reason.MEDIA_NOT_SAVED, "+15551234567").texte()

        assertThat(texte.lowercase()).doesNotContain("download")
        assertThat(texte.lowercase()).contains("saved")
    }

    /** Le tap ouvre la fenêtre de CE motif, et l'intent ne porte rien d'autre que le motif. */
    @Test
    fun `le tap mene a la fenetre d'explication du motif`() {
        MmsFailureNotifier.Reason.entries.forEach { reason ->
            nm.cancelAll()
            val tap = shadowOf(poste(reason, "+15551234567").contentIntent).savedIntent

            assertThat(tap.action).isEqualTo(MmsFailureNotifier.ACTION_EXPLAIN_MMS_FAILURE)
            assertThat(MmsFailureNotifier.raisonDepuisExtra(tap.getStringExtra(MmsFailureNotifier.EXTRA_REASON)))
                .isEqualTo(reason)
            assertThat(tap.extras?.keySet()).containsExactly(MmsFailureNotifier.EXTRA_REASON)
        }
    }

    /**
     * 2026-11-06 — Visible/Verizon : chaque MMS reçu échoue. Le code d'Android et le statut HTTP vont de la
     * notification POSTÉE jusqu'à la lecture que fait `MainActivity`, sans adb.
     */
    @Test
    fun `echec de telechargement - le tap porte le code d'Android et le statut HTTP`() {
        val echec = MmsFailureNotifier.Reason.DOWNLOAD_FAILED
        val notification = poste(echec, "+15551234567", codeAndroid = 4, codeHttp = 403)
        val tap = shadowOf(notification.contentIntent).savedIntent

        assertThat(tap.getIntExtra(MmsFailureNotifier.EXTRA_CODE_ANDROID, 0)).isEqualTo(4)
        assertThat(tap.getIntExtra(MmsFailureNotifier.EXTRA_STATUT_HTTP, 0)).isEqualTo(403)
        assertThat(MmsFailureNotifier.erreurDepuisExtras(echec, tap))
            .isEqualTo(MmsFailureNotifier.ErreurAndroid(4, 403))
    }

    /** Contrôle négatif : hors plage (-1 est `RESULT_OK`), ou pour un autre motif, l'intent ne porte que le motif. */
    @Test
    fun `code hors plage ou autre motif - l'intent ne porte que le motif`() {
        listOf(
            MmsFailureNotifier.Reason.DOWNLOAD_FAILED to -1,
            MmsFailureNotifier.Reason.DOWNLOAD_FAILED to 0,
            MmsFailureNotifier.Reason.TOO_LARGE to 4,
            MmsFailureNotifier.Reason.CARRIER_ERROR to 4,
        ).forEach { (reason, code) ->
            nm.cancelAll()
            val notification = poste(reason, "+15551234567", codeAndroid = code, codeHttp = 403)
            val tap = shadowOf(notification.contentIntent).savedIntent

            assertThat(tap.extras?.keySet()).containsExactly(MmsFailureNotifier.EXTRA_REASON)
        }
    }

    /** `MainActivity` est exportée : un intent forgé ne fait jamais afficher une chaîne ni un nombre hors plage. */
    @Test
    fun `intent forge - chaine, autre type ou hors plage, rien`() {
        val echec = MmsFailureNotifier.Reason.DOWNLOAD_FAILED
        fun forge(remplir: Intent.() -> Unit) = Intent(MmsFailureNotifier.ACTION_EXPLAIN_MMS_FAILURE).apply(remplir)
        fun lu(reason: MmsFailureNotifier.Reason = echec, remplir: Intent.() -> Unit) =
            MmsFailureNotifier.erreurDepuisExtras(reason, forge(remplir))
        val code = MmsFailureNotifier.EXTRA_CODE_ANDROID

        assertThat(lu {}).isNull()
        assertThat(lu { putExtra(code, "4") }).isNull()
        assertThat(lu { putExtra(code, 4L) }).isNull()
        assertThat(lu { putExtra(code, 1_000) }).isNull()
        // Un statut forgé en chaîne est écarté ; le code valide reste.
        val statutEnChaine = lu {
            putExtra(code, 4)
            putExtra(MmsFailureNotifier.EXTRA_STATUT_HTTP, "403 <b>appelez le 0800…</b>")
        }
        assertThat(statutEnChaine).isEqualTo(MmsFailureNotifier.ErreurAndroid(4))
        // Un code valide pour un autre motif ne passe pas.
        assertThat(lu(MmsFailureNotifier.Reason.TOO_LARGE) { putExtra(code, 4) }).isNull()
    }

    /** L'en-tête `From:` d'un PDU porte le suffixe de passerelle : il s'affichait tel quel. */
    @Test
    fun `adresse du PDU - sans suffixe de passerelle ni caractere invisible`() {
        // U+202E (RIGHT-TO-LEFT OVERRIDE), construit par son code : en clair, il serait invisible ici aussi.
        val rlo = Char(0x202E)
        val texte = poste(MmsFailureNotifier.Reason.DOWNLOAD_FAILED, "+1555${rlo}1234567/TYPE=PLMN").texte()

        assertThat(texte).contains("+15551234567")
        assertThat(texte).doesNotContain("/TYPE=PLMN")
        assertThat(texte).doesNotContain(rlo.toString())
    }

    /** Contrôle négatif : la règle de confidentialité garde le dernier mot. */
    @Test
    fun `apercus masques - l'adresse n'apparait pas`() {
        coEvery { visibilite.verdictPour(any(), any()) } returns CorrespondentVisibilityPolicy.Verdict.ANONYMISER

        val texte = poste(MmsFailureNotifier.Reason.TOO_LARGE, "+15551234567", 2_000_000L).texte()

        assertThat(texte).doesNotContain("5551234567")
    }

    @Test
    fun `session leurre ou coffre - rien n'est poste`() {
        coEvery { visibilite.verdictPour(any(), any()) } returns CorrespondentVisibilityPolicy.Verdict.TAIRE

        runBlocking {
            MmsFailureNotifier(contexte, visibilite).notifyFailure(MmsFailureNotifier.Reason.TOO_LARGE, "+15551234567")
        }

        assertThat(shadowOf(nm).allNotifications).isEmpty()
    }

    // ── 2026-11-14 — le nouvel essai (courriel : MMS perdus en Wi-Fi, code 3) ──────────────────────────────────

    private fun demande(transactionId: String = "T1") = requireNotNull(
        RelanceMms.Demande.creer(
            adresse = "http://mmsc.example/a",
            transactionId = transactionId,
            expediteur = "+15551234567",
            subId = 1,
            transactionIdOctets = byteArrayOf(1),
            tentative = 0,
        ),
    )

    private fun posteAvecRelance(
        demande: RelanceMms.Demande,
        nouvelEssaiPrevu: Boolean,
        reason: MmsFailureNotifier.Reason = MmsFailureNotifier.Reason.DOWNLOAD_FAILED,
    ): Boolean = runBlocking {
        MmsFailureNotifier(contexte, visibilite).notifyFailure(
            reason = reason,
            senderAddress = "+15551234567",
            codeAndroid = 3,
            relance = demande,
            nouvelEssaiPrevu = nouvelEssaiPrevu,
        )
    }

    @Test
    fun `echec avec essai prevu - le texte l'annonce, le bouton Reessayer redemande CE message`() {
        val d = demande()

        assertThat(posteAvecRelance(d, nouvelEssaiPrevu = true)).isTrue()
        val notification = shadowOf(nm).allNotifications.single()

        assertThat(notification.texte()).isEqualTo(
            contexte.getString(R.string.mms_failure_notification_body_download_retrying, "+15551234567"),
        )
        val action = notification.actions.single()
        assertThat(action.title.toString()).isEqualTo(contexte.getString(R.string.action_retry))
        val bouton = shadowOf(action.actionIntent).savedIntent
        assertThat(bouton.component?.className).isEqualTo(RelanceMmsReceiver::class.java.name)
        assertThat(bouton.action).isEqualTo(RelanceMmsReceiver.ACTION_MANUELLE)
        assertThat(RelanceMms.Demande.depuis(bouton)?.etiquette).isEqualTo(d.etiquette)
        assertThat(shadowOf(action.actionIntent).isImmutable).isTrue()
    }

    /** Elle représente un message qui attend : un tap pour lire les détails ne doit pas arrêter les essais. */
    @Test
    fun `echec avec bouton - la notification reste apres un tap, et l'essai automatique la trouve`() {
        val d = demande()
        posteAvecRelance(d, nouvelEssaiPrevu = true)
        val notification = shadowOf(nm).allNotifications.single()
        val notifier = MmsFailureNotifier(contexte, visibilite)

        assertThat(notification.flags and Notification.FLAG_AUTO_CANCEL).isEqualTo(0)
        assertThat(notifier.relanceAffichee(d.etiquette)).isTrue()

        notifier.retirerRelance(d.etiquette)

        assertThat(shadowOf(nm).allNotifications).isEmpty()
        assertThat(notifier.relanceAffichee(d.etiquette)).isFalse()
    }

    /** Plus d'essai automatique : le texte ne promet rien, le bouton reste. */
    @Test
    fun `echec sans essai prevu - texte d'echec, bouton quand meme`() {
        posteAvecRelance(demande(), nouvelEssaiPrevu = false)
        val notification = shadowOf(nm).allNotifications.single()

        assertThat(notification.texte()).isEqualTo(
            contexte.getString(R.string.mms_failure_notification_body_download_failed, "+15551234567"),
        )
        assertThat(notification.actions).hasLength(1)
    }

    /** Le tap transmet l'ÉTIQUETTE du message à la fenêtre, qui retrouve par elle le bouton que nous avons créé. */
    @Test
    fun `le tap transmet l'etiquette, et la fenetre retrouve le bouton du message`() {
        val d = demande()
        posteAvecRelance(d, nouvelEssaiPrevu = true)
        val tap = shadowOf(shadowOf(nm).allNotifications.single().contentIntent).savedIntent

        assertThat(tap.getStringExtra(MmsFailureNotifier.EXTRA_RELANCE)).isEqualTo(d.etiquette)
        val relance = MmsFailureNotifier.relanceDepuisExtras(MmsFailureNotifier.Reason.DOWNLOAD_FAILED, tap, contexte)

        assertThat(relance).isNotNull()
        assertThat(RelanceMms.Demande.depuis(shadowOf(relance).savedIntent)?.etiquette).isEqualTo(d.etiquette)
        // Contrôle négatif : pour un autre motif, rien.
        assertThat(MmsFailureNotifier.relanceDepuisExtras(MmsFailureNotifier.Reason.TOO_LARGE, tap, contexte)).isNull()
    }

    /**
     * Avant : une notification par EXPÉDITEUR. Avec un bouton, le second échec aurait remplacé le premier, et
     * « Réessayer » sur l'un aurait redemandé l'autre.
     */
    @Test
    fun `deux messages du meme expediteur - deux notifications, deux boutons distincts`() {
        val a = demande("A")
        val b = demande("B")
        posteAvecRelance(a, nouvelEssaiPrevu = true)
        posteAvecRelance(b, nouvelEssaiPrevu = true)

        val etiquettes = shadowOf(nm).allNotifications.map { notification ->
            val tap = shadowOf(notification.contentIntent).savedIntent
            val relance = MmsFailureNotifier.relanceDepuisExtras(
                reason = MmsFailureNotifier.Reason.DOWNLOAD_FAILED,
                intent = tap,
                context = contexte,
            )
            RelanceMms.Demande.depuis(shadowOf(relance).savedIntent)?.etiquette
        }

        assertThat(etiquettes).containsExactly(a.etiquette, b.etiquette)
    }

    /** Contrôle négatif : seul un téléchargement manqué se redemande. */
    @Test
    fun `autre motif avec une demande - ni bouton ni etiquette`() {
        val d = demande()
        posteAvecRelance(d, nouvelEssaiPrevu = true, reason = MmsFailureNotifier.Reason.CARRIER_ERROR)
        val notification = shadowOf(nm).allNotifications.single()

        assertThat(notification.actions).isNull()
        assertThat(notification.flags and Notification.FLAG_AUTO_CANCEL).isNotEqualTo(0)
        assertThat(MmsFailureNotifier(contexte, visibilite).relanceAffichee(d.etiquette)).isFalse()
    }

    /** Sans notification, l'appelant n'arme aucun essai : il lui faut le savoir. */
    @Test
    fun `coffre ou session leurre - rien de poste, et l'appelant le sait`() {
        coEvery { visibilite.verdictPour(any(), any()) } returns CorrespondentVisibilityPolicy.Verdict.TAIRE

        assertThat(posteAvecRelance(demande(), nouvelEssaiPrevu = true)).isFalse()
        assertThat(shadowOf(nm).allNotifications).isEmpty()
    }

    /**
     * Relecture Claude du 2026-11-14 : le correspondant passe au coffre APRÈS le premier échec. L'essai suivant, qui
     * échoue aussi, ne peut plus le nommer — et la notification nominative déjà affichée ne doit pas rester.
     */
    @Test
    fun `correspondant passe au coffre depuis - sa notification nominative part`() {
        val d = demande()
        posteAvecRelance(d, nouvelEssaiPrevu = true)
        assertThat(shadowOf(nm).allNotifications).hasSize(1)

        coEvery { visibilite.verdictPour(any(), any()) } returns CorrespondentVisibilityPolicy.Verdict.TAIRE
        assertThat(posteAvecRelance(d, nouvelEssaiPrevu = true)).isFalse()

        assertThat(shadowOf(nm).allNotifications).isEmpty()
    }

    /**
     * Relecture Claude du 2026-11-14 : notifications de l'appli coupées, ou canal des échecs réduit au silence —
     * `notify` ne lève rien, mais rien ne s'affiche. L'appelant ne doit pas croire la notification postée (il
     * armerait un essai que sa porte abandonnerait aussitôt).
     */
    @Test
    fun `notifications coupees ou canal muet - rien de poste, et l'appelant le sait`() {
        // Témoin positif : canal créé, notifications permises — la notification est postée.
        NotificationChannelInitializer(contexte).ensureDefaultChannels()
        assertThat(posteAvecRelance(demande("A"), nouvelEssaiPrevu = true)).isTrue()

        shadowOf(nm).setNotificationsEnabled(false)
        assertThat(posteAvecRelance(demande("B"), nouvelEssaiPrevu = true)).isFalse()

        shadowOf(nm).setNotificationsEnabled(true)
        nm.deleteNotificationChannel(NotificationChannelInitializer.CHANNEL_FAILED)
        val muet = android.app.NotificationChannel(
            NotificationChannelInitializer.CHANNEL_FAILED,
            "x",
            NotificationManager.IMPORTANCE_NONE,
        )
        nm.createNotificationChannel(muet)
        assertThat(posteAvecRelance(demande("C"), nouvelEssaiPrevu = true)).isFalse()
    }

    /**
     * `MainActivity` est exportée : l'intent peut venir de n'importe qui. Il ne porte qu'une étiquette ; le bouton est
     * celui que NOUS avons créé pour ce message, ou rien. Un `PendingIntent` glissé dans l'intent n'est plus lu.
     */
    @Test
    fun `intent forge vers la fenetre - etiquette inventee, mal formee ou PendingIntent, rien`() {
        val cle = MmsFailureNotifier.EXTRA_RELANCE
        val echec = MmsFailureNotifier.Reason.DOWNLOAD_FAILED
        val inventee = "a".repeat(64)
        val notreBouton = RelanceMmsReceiver.intentionManuelle(contexte, demande())

        fun lu(intent: Intent) = MmsFailureNotifier.relanceDepuisExtras(echec, intent, contexte)

        assertThat(lu(Intent("x"))).isNull()
        assertThat(lu(Intent("x").putExtra(cle, inventee))).isNull()
        assertThat(lu(Intent("x").putExtra(cle, "../../etc"))).isNull()
        assertThat(lu(Intent("x").putExtra(cle, notreBouton))).isNull()
        // Témoin positif : la vraie étiquette d'un message dont le bouton existe.
        assertThat(lu(Intent("x").putExtra(cle, demande().etiquette))).isNotNull()
    }
}
