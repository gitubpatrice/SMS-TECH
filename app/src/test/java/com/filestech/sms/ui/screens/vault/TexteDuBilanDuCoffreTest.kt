package com.filestech.sms.ui.screens.vault

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.sms.ui.screens.vault.VaultViewModel.Event.Suppression
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 2026-11-27 (issue #108) — **le bilan d'une suppression groupée du Coffre, avec les vraies ressources** : le bon
 * pluriel, le rouge pour ce qui est resté, et la cause quand la copie du téléphone a retenu les conversations —
 * réessayer tel quel n'y changerait rien.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TexteDuBilanDuCoffreTest {

    private val res: Resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun supprimeesAuBonPluriel() {
        assertThat(texteDuBilanDuCoffre(res, Suppression.Supprimees(nombre = 1)))
            .isEqualTo("1 conversation deleted" to false)
        assertThat(texteDuBilanDuCoffre(res, Suppression.Supprimees(nombre = 3)))
            .isEqualTo("3 conversations deleted" to false)
    }

    @Test
    fun retenuesParLaCopieDuTelephone_laCauseEtQuoiFaire() {
        val (une, rouge) = texteDuBilanDuCoffre(res, Suppression.Conservees(nombre = 1, copieSysteme = true))
        assertThat(une).startsWith("1 vault conversation was not deleted: its copy in the phone’s message store")
        // Au conditionnel (relecture Claude) : la copie peut aussi résister alors que SMS Tech est déjà par défaut.
        assertThat(une).contains("If SMS Tech is not your default SMS app, make it so, then try again.")
        assertThat(rouge).isTrue()
        assertThat(texteDuBilanDuCoffre(res, Suppression.Conservees(nombre = 2, copieSysteme = true)).first)
            .startsWith("2 vault conversations were not deleted: their copy")
    }

    /** Témoin : une autre cause garde le texte de la liste, sans parler de l'application par défaut. */
    @Test
    fun autreCause_leTexteDeLaListe() {
        val (texte, rouge) = texteDuBilanDuCoffre(res, Suppression.Conservees(nombre = 2, copieSysteme = false))
        assertThat(texte).startsWith("2 conversations were not deleted: part of them could not be removed")
        assertThat(texte).doesNotContain("default SMS app")
        assertThat(rouge).isTrue()
    }

    @Test
    @Config(qualifiers = "fr")
    fun enFrancais() {
        assertThat(texteDuBilanDuCoffre(res, Suppression.Conservees(nombre = 1, copieSysteme = true)).first)
            .startsWith("1 conversation du coffre n’a pas été supprimée")
        assertThat(texteDuBilanDuCoffre(res, Suppression.Conservees(nombre = 2, copieSysteme = true)).first)
            .startsWith("2 conversations du coffre n’ont pas été supprimées")
    }
}
