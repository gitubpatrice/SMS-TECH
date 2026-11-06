package com.filestech.sms.system.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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

    private fun poste(reason: MmsFailureNotifier.Reason, expediteur: String?, taille: Long? = null): Notification {
        runBlocking { MmsFailureNotifier(contexte, visibilite).notifyFailure(reason, expediteur, taille) }
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
}
