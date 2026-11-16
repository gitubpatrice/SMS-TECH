package com.filestech.sms.ui.screens.conversations

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.sms.ui.screens.conversations.ConversationsViewModel.Event.Groupe
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 2026-11-16 (v1.31.0) — **le bilan d'une action groupée, avec les vraies ressources** : le bon pluriel, et le rouge
 * pour ce qui n'a pas abouti (échec d'archivage, conversations conservées), jamais pour un succès.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TexteDuBilanTest {

    private val res: Resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun succesEnBleuAuBonPluriel() {
        assertThat(texteDuBilan(res, Groupe.Archivees(nombre = 1, archivees = true)))
            .isEqualTo("1 conversation archived" to false)
        assertThat(texteDuBilan(res, Groupe.Archivees(nombre = 3, archivees = false)))
            .isEqualTo("3 conversations moved out of the archive" to false)
        assertThat(texteDuBilan(res, Groupe.Supprimees(nombre = 2)))
            .isEqualTo("2 conversations deleted" to false)
    }

    @Test
    fun ceQuiNAPasAbouti_enRouge() {
        val (conservee, rouge) = texteDuBilan(res, Groupe.SuppressionsConservees(nombre = 1))
        assertThat(conservee).startsWith("1 conversation was not deleted")
        assertThat(rouge).isTrue()
        assertThat(texteDuBilan(res, Groupe.SuppressionsConservees(nombre = 4)).first)
            .startsWith("4 conversations were not deleted")
        assertThat(texteDuBilan(res, Groupe.ArchivageEchoue))
            .isEqualTo("The selected conversations could not be moved. Try again." to true)
    }

    @Test
    @Config(qualifiers = "fr")
    fun enFrancais() {
        assertThat(texteDuBilan(res, Groupe.Supprimees(nombre = 2)).first).isEqualTo("2 conversations supprimées")
        assertThat(texteDuBilan(res, Groupe.Archivees(nombre = 1, archivees = true)).first)
            .isEqualTo("1 conversation archivée")
    }
}
