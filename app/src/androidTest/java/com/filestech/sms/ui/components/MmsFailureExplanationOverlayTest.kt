package com.filestech.sms.ui.components

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.R
import com.filestech.sms.data.mms.PdusEnAttente
import com.filestech.sms.system.notifications.MmsFailureExplanationHolder
import com.filestech.sms.system.notifications.MmsFailureNotifier
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * 2026-11-06 — la fenêtre « MMS non reçu » ouverte par le tap de sa notification.
 *
 * Elle remplace un tap qui menait à la liste des conversations sous un texte promettant des « paramètres
 * MMS » inexistants. Ce test la compose réellement et vérifie les gardes qu'elle partage avec la
 * confirmation Safety call : rien en session leurre (le porteur est vidé), attente tant que l'application
 * est verrouillée, rien pour un tap périmé, et le porteur vidé seulement à la fermeture.
 */
class MmsFailureExplanationOverlayTest {

    @get:Rule val compose = createComposeRule()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val texteTropVolumineux: String
        get() = context.getString(
            R.string.mms_failure_dialog_body_too_large,
            (PdusEnAttente.PLAFOND_OCTETS / (1024L * 1024L)).toInt(),
        )
    private val texteEchec: String get() = context.getString(R.string.mms_failure_dialog_body_download_failed)
    private val texteFichierNonEnregistre: String get() = context.getString(R.string.mms_failure_dialog_body_media_not_saved)
    private val texteOperateur: String get() = context.getString(R.string.mms_failure_dialog_body_carrier_error)
    private val boutonReseau: String get() = context.getString(R.string.mms_failure_dialog_network_settings)
    private val boutonStockage: String get() = context.getString(R.string.mms_failure_dialog_storage_settings)
    private val boutonFermer: String get() = context.getString(R.string.action_close)

    /** 2026-11-06 — la ligne attendue pour l'erreur (4, 403) : `MMS_ERROR_HTTP_FAILURE`, HTTP 403. */
    private val ligneCodeEtStatutHttp: String
        get() = context.getString(R.string.mms_failure_dialog_android_error_http, 4, 403)

    @Test
    fun tropVolumineuxLaFenetreExpliqueSansBoutonReseauEtSeFerme() {
        val holder = MmsFailureExplanationHolder().apply { set(MmsFailureNotifier.Reason.TOO_LARGE) }
        compose.setContent { MmsFailureExplanationOverlay(holder, showLock = false, isPanicDecoy = false) }

        compose.onNodeWithText(texteTropVolumineux).assertIsDisplayed()
        // Le texte donne la taille réelle que l'application peut recevoir : 4 Mo.
        assertThat(texteTropVolumineux).contains("4")
        compose.onNodeWithText(boutonReseau).assertDoesNotExist()

        compose.onNodeWithText(boutonFermer).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(texteTropVolumineux).assertDoesNotExist()
        assertThat(holder.pending.value).isNull()
    }

    @Test
    fun echecDeTelechargementLaFenetreProposeLesReglagesReseau() {
        val holder = MmsFailureExplanationHolder().apply { set(MmsFailureNotifier.Reason.DOWNLOAD_FAILED) }
        compose.setContent { MmsFailureExplanationOverlay(holder, showLock = false, isPanicDecoy = false) }

        compose.onNodeWithText(texteEchec).assertIsDisplayed()
        compose.onNodeWithText(boutonReseau).assertIsDisplayed()
        compose.onNodeWithText(boutonStockage).assertDoesNotExist()
        compose.onNodeWithText(boutonFermer).assertIsDisplayed()
    }

    /** 2026-11-06 — Visible/Verizon : le code d'Android et le statut HTTP, que l'utilisateur peut nous transmettre. */
    @Test
    fun echecDeTelechargementAvecCodeLaFenetreAfficheLeCodeEtLeStatutHttp() {
        val holder = MmsFailureExplanationHolder().apply {
            set(MmsFailureNotifier.Reason.DOWNLOAD_FAILED, erreur = MmsFailureNotifier.ErreurAndroid(4, 403))
        }
        compose.setContent { MmsFailureExplanationOverlay(holder, showLock = false, isPanicDecoy = false) }

        compose.onNodeWithText(texteEchec).assertIsDisplayed()
        compose.onNodeWithText(ligneCodeEtStatutHttp).assertIsDisplayed()
    }

    @Test
    fun echecDeTelechargementSansStatutHttpLaFenetreAfficheLeCodeSeul() {
        val holder = MmsFailureExplanationHolder().apply {
            set(MmsFailureNotifier.Reason.DOWNLOAD_FAILED, erreur = MmsFailureNotifier.ErreurAndroid(8))
        }
        compose.setContent { MmsFailureExplanationOverlay(holder, showLock = false, isPanicDecoy = false) }

        val ligneCodeSeul = context.getString(R.string.mms_failure_dialog_android_error, 8)
        compose.onNodeWithText(ligneCodeSeul).assertIsDisplayed()
    }

    /** Contrôle négatif : une erreur portée par un autre motif ne s'affiche pas. */
    @Test
    fun autreMotifAvecErreurLaFenetreNAfficheAucunCode() {
        val holder = MmsFailureExplanationHolder().apply {
            set(MmsFailureNotifier.Reason.TOO_LARGE, erreur = MmsFailureNotifier.ErreurAndroid(4, 403))
        }
        compose.setContent { MmsFailureExplanationOverlay(holder, showLock = false, isPanicDecoy = false) }

        compose.onNodeWithText(texteTropVolumineux).assertIsDisplayed()
        compose.onNodeWithText(ligneCodeEtStatutHttp).assertDoesNotExist()
    }

    /** Le message est arrivé : c'est le stockage qu'il faut regarder, pas le réseau. */
    @Test
    fun fichierNonEnregistreLaFenetreProposeLeStockageEtPasLeReseau() {
        val holder = MmsFailureExplanationHolder().apply { set(MmsFailureNotifier.Reason.MEDIA_NOT_SAVED) }
        compose.setContent { MmsFailureExplanationOverlay(holder, showLock = false, isPanicDecoy = false) }

        compose.onNodeWithText(texteFichierNonEnregistre).assertIsDisplayed()
        compose.onNodeWithText(boutonStockage).assertIsDisplayed()
        compose.onNodeWithText(boutonReseau).assertDoesNotExist()
    }

    /** Refus de l'opérateur : rien à régler sur le téléphone, donc aucun bouton de réglages. */
    @Test
    fun refusDeLOperateurAucunBoutonDeReglages() {
        val holder = MmsFailureExplanationHolder().apply { set(MmsFailureNotifier.Reason.CARRIER_ERROR) }
        compose.setContent { MmsFailureExplanationOverlay(holder, showLock = false, isPanicDecoy = false) }

        compose.onNodeWithText(texteOperateur).assertIsDisplayed()
        compose.onNodeWithText(boutonReseau).assertDoesNotExist()
        compose.onNodeWithText(boutonStockage).assertDoesNotExist()
        compose.onNodeWithText(boutonFermer).assertIsDisplayed()
    }

    /** Une fois parue, la fenêtre est marquée : ni rotation ni retour par le verrou ne la feront périmer. */
    @Test
    fun uneFenetreAfficheeEstMarqueeEtNePerimePlus() {
        val holder = MmsFailureExplanationHolder().apply { set(MmsFailureNotifier.Reason.TOO_LARGE) }
        compose.setContent { MmsFailureExplanationOverlay(holder, showLock = false, isPanicDecoy = false) }
        compose.waitForIdle()

        compose.onNodeWithText(texteTropVolumineux).assertIsDisplayed()
        assertThat(holder.pending.value?.affichee).isTrue()
    }

    /** Verrouillée : rien, et la fenêtre attend ; déverrouillée : elle s'ouvre. */
    @Test
    fun applicationVerrouilleeLaFenetreAttendLeDeverrouillage() {
        val holder = MmsFailureExplanationHolder().apply { set(MmsFailureNotifier.Reason.TOO_LARGE) }
        val verrou = mutableStateOf(true)
        compose.setContent { MmsFailureExplanationOverlay(holder, showLock = verrou.value, isPanicDecoy = false) }

        compose.onNodeWithText(texteTropVolumineux).assertDoesNotExist()
        assertThat(holder.pending.value).isNotNull()

        verrou.value = false
        compose.waitForIdle()

        compose.onNodeWithText(texteTropVolumineux).assertIsDisplayed()
    }

    /** En session leurre, rien ne s'affiche, et rien n'attend la sortie du leurre. */
    @Test
    fun sessionLeurreRienEtLePorteurEstVide() {
        val holder = MmsFailureExplanationHolder().apply { set(MmsFailureNotifier.Reason.TOO_LARGE) }
        compose.setContent { MmsFailureExplanationOverlay(holder, showLock = false, isPanicDecoy = true) }
        compose.waitForIdle()

        compose.onNodeWithText(texteTropVolumineux).assertDoesNotExist()
        assertThat(holder.pending.value).isNull()
    }

    @Test
    fun tapPerimeRienEtLePorteurEstVide() {
        val holder = MmsFailureExplanationHolder().apply {
            set(
                MmsFailureNotifier.Reason.TOO_LARGE,
                nowMs = System.currentTimeMillis() - MmsFailureExplanationHolder.PENDING_TTL_MS - 1_000L,
            )
        }
        compose.setContent { MmsFailureExplanationOverlay(holder, showLock = false, isPanicDecoy = false) }
        compose.waitForIdle()

        compose.onNodeWithText(texteTropVolumineux).assertDoesNotExist()
        assertThat(holder.pending.value).isNull()
    }
}
