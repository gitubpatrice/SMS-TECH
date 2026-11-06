package com.filestech.sms.data.mms

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-11-06 — où part la réponse au MMSC, et quelles adresses on accepte de viser.
 *
 * Même règle qu'AOSP Messaging : l'adresse de téléchargement n'est visée que si l'opérateur le
 * demande (`MMS_CONFIG_NOTIFY_WAP_MMSC_ENABLED`) ; sinon `null`, et Android prend le MMSC de l'APN.
 * L'adresse vient de la notification WAP-Push, donc de l'extérieur : seule une adresse `http(s)` passe,
 * comme pour le téléchargement lui-même (v1.3.10, SEC-05).
 */
class ReponseAuMmscTest {

    @Test
    fun `cas general - le MMSC de l'APN, jamais l'adresse du message`() {
        assertThat(adresseDeLaReponse(false, "http://mmsc.example/abc")).isNull()
    }

    @Test
    fun `operateur qui le demande - l'adresse de telechargement`() {
        assertThat(adresseDeLaReponse(true, "http://mmsc.example/abc")).isEqualTo("http://mmsc.example/abc")
        assertThat(adresseDeLaReponse(true, "HTTPS://mmsc.example/abc")).isEqualTo("HTTPS://mmsc.example/abc")
    }

    /** Contrôle négatif : une adresse qui n'est pas `http(s)` n'est jamais visée, même sur demande. */
    @Test
    fun `adresse non http - repli sur le MMSC de l'APN`() {
        listOf("file:///data/x", "content://x/y", "javascript:x", "", "mmsc.example/abc").forEach { adresse ->
            assertThat(adresseDeLaReponse(true, adresse)).isNull()
        }
        assertThat(adresseDeLaReponse(true, null)).isNull()
    }

    @Test
    fun `adresseHttp - http et https seulement, sans tenir compte de la casse`() {
        assertThat(MmsDownloader.adresseHttp("http://a/b")).isTrue()
        assertThat(MmsDownloader.adresseHttp("https://a/b")).isTrue()
        assertThat(MmsDownloader.adresseHttp("HtTp://a/b")).isTrue()
        assertThat(MmsDownloader.adresseHttp("file:///a")).isFalse()
        assertThat(MmsDownloader.adresseHttp("content://a")).isFalse()
        assertThat(MmsDownloader.adresseHttp("httpx://a")).isFalse()
        assertThat(MmsDownloader.adresseHttp("http:/a")).isFalse()
    }
}
