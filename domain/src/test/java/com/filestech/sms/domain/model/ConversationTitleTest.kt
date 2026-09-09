package com.filestech.sms.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.28.3 — **une seule règle de titre**, `nom choisi > nom résolu > numéros`. Avant, six sites
 * recalculaient `displayName ?: numéros` chacun de leur côté (liste, fil, PDF, dialogue de
 * réaction…) : un nom choisi aurait dû être ajouté six fois.
 */
class ConversationTitleTest {

    private fun conv(customName: String?, displayName: String?, vararg adresses: String) = Conversation(
        id = 1L,
        threadId = null,
        addresses = adresses.map { PhoneAddress.of(it) },
        displayName = displayName,
        lastMessageAt = 0L,
        lastMessagePreview = null,
        unreadCount = 0,
        pinned = false,
        archived = false,
        muted = false,
        inVault = false,
        draft = null,
        customName = customName,
    )

    @Test
    fun `le nom choisi prime sur le nom resolu`() {
        assertThat(conv("Famille", "Pat, 0607231541", "0617332729", "0607231541").title).isEqualTo("Famille")
    }

    @Test
    fun `sans nom choisi le nom resolu puis les numeros`() {
        assertThat(conv(null, "Pat", "0617332729").title).isEqualTo("Pat")
        assertThat(conv(null, null, "0617332729", "0607231541").title).isEqualTo("0617332729, 0607231541")
        assertThat(conv("  ", "", "0617332729").title).isEqualTo("0617332729")
    }
}
