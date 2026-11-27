package com.filestech.sms.ui.screens.compose

import android.content.res.Configuration
import android.content.res.Resources
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.domain.model.ContactPhone
import com.filestech.sms.domain.model.PhoneAddress
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

/**
 * 2026-11-27 (issue #109) — le type affiché devant chaque numéro d'un contact dans « Nouveau message ».
 *
 * Sur un vrai appareil, avec les chaînes d'Android : le type standard est nommé, le libellé de l'utilisateur est
 * repris, et rien ne s'affiche quand il n'y a rien à dire — `Phone.getTypeLabel` rendrait alors « Personnalisé ».
 * Le libellé suit la langue des ressources passées, donc celle de l'application.
 */
class LibelleDuTypeTest {

    private val res: Resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun numero(type: Int, label: String? = null) =
        ContactPhone(PhoneAddress.of("0611111111"), type = type, label = label)

    private fun ressourcesEn(locale: Locale): Resources {
        val config = Configuration(res.configuration).apply { setLocale(locale) }
        return InstrumentationRegistry.getInstrumentation().targetContext.createConfigurationContext(config).resources
    }

    @Test
    fun unTypeStandardEstNommeParAndroid() {
        val mobile = libelleDuType(res, numero(Phone.TYPE_MOBILE))

        assertThat(mobile).isEqualTo(Phone.getTypeLabel(res, Phone.TYPE_MOBILE, null).toString())
        assertThat(mobile).isNotEqualTo(Phone.getTypeLabel(res, Phone.TYPE_CUSTOM, null).toString())
        assertThat(mobile).isNotEmpty()
    }

    @Test
    fun leLibelleDeLUtilisateurEstRepris() {
        assertThat(libelleDuType(res, numero(ContactPhone.TYPE_LIBRE, "Chalet"))).isEqualTo("Chalet")
        assertThat(libelleDuType(res, numero(Phone.TYPE_ASSISTANT, "Sophie"))).isEqualTo("Sophie")
        assertThat(libelleDuType(res, numero(TYPE_HORS_ANDROID, "Bateau"))).isEqualTo("Bateau")
    }

    /** Témoin : sans libellé, Android dirait « Personnalisé » — ce mot ne s'affiche pas. */
    @Test
    fun sansRienADireRienNeSAffiche() {
        assertThat(Phone.getTypeLabel(res, Phone.TYPE_CUSTOM, null).toString()).isNotEmpty()

        assertThat(libelleDuType(res, numero(ContactPhone.TYPE_LIBRE))).isNull()
        assertThat(libelleDuType(res, numero(ContactPhone.TYPE_LIBRE, "  "))).isNull()
        assertThat(libelleDuType(res, numero(TYPE_HORS_ANDROID))).isNull()
    }

    @Test
    fun leLibelleSuitLaLangueDesRessources() {
        val anglais = libelleDuType(ressourcesEn(Locale.ENGLISH), numero(Phone.TYPE_HOME))
        val francais = libelleDuType(ressourcesEn(Locale.FRENCH), numero(Phone.TYPE_HOME))

        assertThat(anglais).isEqualTo("Home")
        assertThat(francais).isNotEqualTo(anglais)
        assertThat(francais).isNotEmpty()
    }

    private companion object {
        /** Un type qu'Android ne connaît pas (les siens vont de 1 à 20). */
        const val TYPE_HORS_ANDROID = 99
    }
}
