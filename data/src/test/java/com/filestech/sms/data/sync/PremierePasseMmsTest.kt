package com.filestech.sms.data.sync

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-11-27 (issue #98) — la première passe des MMS, celle qui marque lu l'historique importé.
 *
 * Ce que ces cas fixent : dans un profil sans aucun SMS, le curseur des SMS reste à zéro ; une fois l'import complet
 * des MMS fait, la passe suivante n'est plus une première, et un MMS recopié par le profil principal y arrive NON lu.
 */
class PremierePasseMmsTest {

    @Test
    fun uneInstallationNeuve_estUnePremierePasse() {
        assertThat(estUnePremierePasseMms(curseurSms = 0L, importMmsComplet = false)).isTrue()
    }

    /** Le cas mesuré : un profil sans SMS dont l'import complet des MMS est fait. */
    @Test
    fun unProfilSansSms_apresSonImportComplet_nEstPlusUnePremierePasse() {
        assertThat(estUnePremierePasseMms(curseurSms = 0L, importMmsComplet = true)).isFalse()
    }

    @Test
    fun desSmsDejaImportes_neFontPasUnePremierePasse() {
        assertThat(estUnePremierePasseMms(curseurSms = 42L, importMmsComplet = false)).isFalse()
        assertThat(estUnePremierePasseMms(curseurSms = 42L, importMmsComplet = true)).isFalse()
    }
}
