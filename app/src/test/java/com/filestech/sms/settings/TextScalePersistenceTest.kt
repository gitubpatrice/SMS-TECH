package com.filestech.sms.settings

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.domain.settings.TextScale
import com.filestech.sms.testing.magasinDeTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 2026-10-29 — le cran `XXL` de la taille du texte des messages, sur le disque.
 *
 * `appearance.textScale` est persisté PAR SON NOM. Deux promesses en découlent, figées ici :
 * le nouveau cran survit à l'écriture et à la relecture, et un nom que la version courante ne
 * connaît pas — ce que lit une version plus ancienne après un retour en arrière, ou une
 * sauvegarde venue d'une version plus récente — retombe sur [TextScale.MEDIUM] sans planter.
 *
 * ⚠️ JUnit 4 exécuté par le moteur *vintage* : corps en bloc, jamais `fun f() = runBlocking {…}`
 * (cf. [SettingsHydrationTest]).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class TextScalePersistenceTest {

    @get:Rule
    val dossier = TemporaryFolder()

    private val porteeMagasin = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val magasin by lazy { magasinDeTest(dossier, porteeMagasin) }

    @After
    fun fermeLeMagasin() {
        porteeMagasin.cancel()
    }

    @Test
    fun leCranLePlusHautSurvitALEcritureEtALaRelecture() {
        runBlocking {
            val portee = CoroutineScope(Dispatchers.Unconfined)
            try {
                val depot = SettingsRepository(magasin, portee)
                depot.update { it.copy(appearance = it.appearance.copy(textScale = TextScale.XXL)) }

                assertThat(magasin.data.first()[TAILLE_DU_TEXTE]).isEqualTo("XXL")
                assertThat(SettingsRepository(magasin, portee).flow.first().appearance.textScale)
                    .isEqualTo(TextScale.XXL)
            } finally {
                portee.cancel()
            }
        }
    }

    @Test
    fun unNomInconnuRetombeSurLaTailleNormale() {
        runBlocking {
            val portee = CoroutineScope(Dispatchers.Unconfined)
            try {
                magasin.edit { it[TAILLE_DU_TEXTE] = "XXXL" }
                assertThat(SettingsRepository(magasin, portee).flow.first().appearance.textScale)
                    .isEqualTo(TextScale.MEDIUM)
            } finally {
                portee.cancel()
            }
        }
    }

    private companion object {
        /** La clé telle qu'elle est écrite sur le disque. */
        val TAILLE_DU_TEXTE = stringPreferencesKey("appearance.textScale")
    }
}
