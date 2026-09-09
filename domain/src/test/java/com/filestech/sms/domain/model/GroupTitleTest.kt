package com.filestech.sms.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.28.3 (audit global, X-08 — mesuré sur le S9) — un groupe s'affichait comme une suite de
 * numéros, le dépôt affirmant que l'écran « joignait les noms ». Ce fichier fixe la règle.
 */
class GroupTitleTest {

    @Test
    fun `les noms des membres, dans l'ordre, un numero brut pour l'inconnu`() {
        val titre = GroupTitle.of(listOf("0617332729" to "Pat", "0607231541" to null))

        assertThat(titre).isEqualTo("Pat, 0607231541")
    }

    /** Contrôle du repli : aucun contact connu → `null`, l'écran garde les numéros comme avant. */
    @Test
    fun `aucun contact connu rend null`() {
        assertThat(GroupTitle.of(listOf("0617332729" to null, "0607231541" to "  "))).isNull()
        assertThat(GroupTitle.of(emptyList())).isNull()
    }
}
