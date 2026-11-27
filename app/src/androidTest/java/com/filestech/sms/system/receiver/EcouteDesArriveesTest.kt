package com.filestech.sms.system.receiver

import android.content.Context
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.data.sms.DefaultSmsAppManager
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 2026-11-27 (issue #98) — **où les arrivées d'un autre profil sont écoutées** ([ArriveesDuProfilPrincipal.aEcouter]),
 * sur le vrai système.
 *
 * Le profil principal reçoit lui-même SMS et MMS, et les notifie : y écouter aussi `SMS_RECEIVED` et le stockage
 * partagé doublerait chaque notification. Le cas d'un profil secondaire ne tourne que lancé dans un tel profil
 * (`am instrument --user <id>`), et chaque cas est SAUTÉ sans le rôle SMS, jamais vert à tort.
 */
@RunWith(AndroidJUnit4::class)
class EcouteDesArriveesTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val utilisateurs: UserManager
        get() = context.getSystemService(UserManager::class.java)

    @Test
    fun leProfilPrincipal_nEcoutePas_memeAvecLeRoleSms() {
        assumeTrue("profil principal seulement", utilisateurs.isSystemUser)
        assumeTrue("SMS Tech doit etre l'application SMS par defaut", DefaultSmsAppManager(context).isDefault())

        assertThat(ArriveesDuProfilPrincipal.aEcouter(context)).isFalse()
    }

    @Test
    fun unProfilSecondaire_ouSmsTechEstParDefaut_ecoute() {
        assumeFalse("profil secondaire seulement", utilisateurs.isSystemUser)
        assumeTrue("SMS Tech doit etre l'application SMS par defaut", DefaultSmsAppManager(context).isDefault())

        assertThat(ArriveesDuProfilPrincipal.aEcouter(context)).isTrue()
    }
}
