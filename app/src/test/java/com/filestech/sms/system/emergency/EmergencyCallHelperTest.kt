package com.filestech.sms.system.emergency

import android.Manifest
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 2026-11-06 — ce que les tuiles d'appel lancent RÉELLEMENT.
 *
 * Android n'autorise que l'app Téléphone à appeler un numéro d'urgence directement : pour toute autre app,
 * un `ACTION_CALL` vers le 112 ouvre le composeur (mesuré sur émulateur API 34 :
 * « Cannot call potential emergency number 112 with CALL Intent »). L'écran l'annonçait pourtant
 * « Appeler directement », et l'ancien `placeCall` demandait `CALL_PHONE` pour rien. Ces tests figent le
 * contrat honnête : les secours → `ACTION_DIAL` ; un proche → `ACTION_CALL` si la permission est accordée.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EmergencyCallHelperTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun lance(): Intent? = shadowOf(app).nextStartedActivity

    @Test
    fun `secours - le composeur s'ouvre avec le numero, meme avec la permission d'appel`() {
        shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE)

        val outcome = EmergencyCallHelper.openDialer(app, "112")

        assertThat(outcome).isEqualTo(EmergencyCallHelper.CallOutcome.SUCCESS)
        val intent = checkNotNull(lance())
        assertThat(intent.action).isEqualTo(Intent.ACTION_DIAL)
        assertThat(intent.data.toString()).isEqualTo("tel:112")
    }

    /** Contrôle négatif : un numéro hors de la liste blanche ne lance rien du tout. */
    @Test
    fun `secours - numero hors liste blanche, rien n'est lance`() {
        val outcome = EmergencyCallHelper.openDialer(app, "0899000000")

        assertThat(outcome).isEqualTo(EmergencyCallHelper.CallOutcome.INVALID_NUMBER)
        assertThat(lance()).isNull()
    }

    @Test
    fun `proche - appel direct quand la permission est accordee`() {
        shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE)

        val outcome = EmergencyCallHelper.placeTrustedContactCall(app, " +33612345678 ")

        assertThat(outcome).isEqualTo(EmergencyCallHelper.CallOutcome.SUCCESS)
        val intent = checkNotNull(lance())
        assertThat(intent.action).isEqualTo(Intent.ACTION_CALL)
        assertThat(intent.data.toString()).isEqualTo("tel:+33612345678")
    }

    /** Sans la permission, rien n'est lancé : l'écran se replie alors sur le composeur. */
    @Test
    fun `proche - sans la permission, refus et aucun appel`() {
        shadowOf(app).denyPermissions(Manifest.permission.CALL_PHONE)

        val outcome = EmergencyCallHelper.placeTrustedContactCall(app, "+33612345678")

        assertThat(outcome).isEqualTo(EmergencyCallHelper.CallOutcome.PERMISSION_DENIED)
        assertThat(lance()).isNull()
    }

    @Test
    fun `proche - repli sur le composeur`() {
        val outcome = EmergencyCallHelper.openTrustedContactDialer(app, "+33612345678")

        assertThat(outcome).isEqualTo(EmergencyCallHelper.CallOutcome.SUCCESS)
        assertThat(checkNotNull(lance()).action).isEqualTo(Intent.ACTION_DIAL)
    }
}
