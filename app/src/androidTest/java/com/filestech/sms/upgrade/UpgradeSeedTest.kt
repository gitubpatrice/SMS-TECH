package com.filestech.sms.upgrade

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.sms.data.local.db.AppDatabase
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Première moitié du contrôle de mise à jour : écrit le jeu d'essai dans la base RÉELLE de
 * l'application, depuis la version PRÉCÉDENTE.
 *
 * Ce fichier est copié dans l'arbre de travail du tag précédent par le job CI, compilé avec la
 * chaîne de l'époque, et exécuté par le paquet de l'époque. C'est la seule façon d'obtenir un
 * fichier `smstech.db` réellement écrit par l'ancienne version de SQLCipher — le sujet même du
 * contrôle. L'APK de release publié ne conviendrait pas : il n'est pas débogable, donc `run-as`
 * n'y donne accès à rien, et aucun test instrumenté ne peut s'y attacher.
 *
 * L'injection passe par Hilt et par [com.filestech.sms.data.local.db.DatabaseFactory] : la base est
 * ouverte par le chemin de production, avec la clé descellée du Keystore, à l'emplacement de
 * production. Reconstruire ici une ouverture « équivalente » à la main serait le défaut classique
 * d'un test de migration — il mesurerait sa propre copie du code, pas le code.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@UpgradeTest
class UpgradeSeedTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject
    lateinit var base: AppDatabase

    @Before
    fun injecter() {
        hilt.inject()
    }

    @Test
    fun semerLeJeuDEssaiDansLaBaseDeLApplication() {
        val db = base.openHelper.writableDatabase
        UpgradeFixture.semer(db)

        // Contrôle immédiat, dans le même processus : sans lui, un semis qui n'écrit rien laisserait
        // la vérification échouer APRÈS la mise à jour, et le job accuserait la mise à jour d'une
        // perte que le semis n'avait jamais produite.
        assertThat(UpgradeFixture.compter(db, "conversations")).isEqualTo(UpgradeFixture.NOMBRE_DE_FILS.toLong())
        assertThat(UpgradeFixture.compter(db, "messages")).isEqualTo(UpgradeFixture.NOMBRE_DE_MESSAGES.toLong())
        assertThat(UpgradeFixture.compter(db, "attachments")).isEqualTo(1L)
        assertThat(UpgradeFixture.compter(db, "scheduled_messages")).isEqualTo(1L)
        assertThat(UpgradeFixture.compter(db, "blocked_numbers")).isEqualTo(1L)

        // Fermer force le point de contrôle du WAL : le job relève ensuite l'empreinte du fichier
        // `smstech.db` lui-même, et un jeu d'essai encore à moitié dans `smstech.db-wal` rendrait
        // cette empreinte — et sa comparaison d'après mise à jour — sans objet.
        base.close()
    }
}
