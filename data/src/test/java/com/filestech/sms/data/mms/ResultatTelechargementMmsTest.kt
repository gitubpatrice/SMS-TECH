package com.filestech.sms.data.mms

import android.app.PendingIntent
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-11-06 — le résultat d'un téléchargement MMS doit rester MUTABLE, comme l'accusé d'envoi
 * ([AccuseDEnvoiMmsTest]) : c'est par lui qu'Android transmet le statut HTTP du MMSC
 * (`SmsManager.EXTRA_MMS_HTTP_STATUS`). En IMMUTABLE, seul le code de résultat arrive, et la fenêtre
 * « MMS non reçu » ne peut pas montrer le statut qu'un utilisateur nous enverrait. Ce test existe pour
 * qu'un durcissement « de sécurité » bien intentionné ne le fasse pas disparaître en silence.
 */
class ResultatTelechargementMmsTest {

    @Test
    fun `Android 12 et plus - mutable, jamais immutable`() {
        listOf(31, 34, 36).forEach { sdk ->
            val drapeaux = drapeauxDuResultatDeTelechargement(sdk)
            assertThat(drapeaux and PendingIntent.FLAG_MUTABLE).isNotEqualTo(0)
            assertThat(drapeaux and PendingIntent.FLAG_IMMUTABLE).isEqualTo(0)
        }
    }

    @Test
    fun `avant Android 12 - jamais immutable (mutable par defaut)`() {
        listOf(26, 30).forEach { sdk ->
            assertThat(drapeauxDuResultatDeTelechargement(sdk) and PendingIntent.FLAG_IMMUTABLE).isEqualTo(0)
        }
    }

    /** Un nouveau téléchargement du même fichier remplace le précédent, comme avant ce correctif. */
    @Test
    fun `le resultat d'un nouveau telechargement remplace l'ancien`() {
        assertThat(drapeauxDuResultatDeTelechargement(34) and PendingIntent.FLAG_UPDATE_CURRENT).isNotEqualTo(0)
    }
}
