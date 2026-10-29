package com.filestech.sms.ui.screens.about

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-10-29 — le lien « Soutenir le projet » de l'écran À propos ouvre la page de dons dans la
 * langue affichée quand files-tech.com la publie (français, allemand), l'anglaise sinon. Les pages
 * italienne et espagnole n'existent pas (404 vérifié le 2026-10-29) : y envoyer serait un lien mort.
 */
class PageDeDonsTest {

    @Test
    fun `francais et allemand - la page dans la langue de l'application`() {
        assertThat(urlPageDeDons("fr")).isEqualTo("https://www.files-tech.com/soutenir.php")
        assertThat(urlPageDeDons("de")).isEqualTo("https://www.files-tech.com/de/soutenir.php")
    }

    @Test
    fun `autres langues - la page anglaise, jamais une page inexistante`() {
        listOf("en", "it", "es", "pt", "").forEach { langue ->
            assertThat(urlPageDeDons(langue)).isEqualTo("https://www.files-tech.com/en/soutenir.php")
        }
    }
}
