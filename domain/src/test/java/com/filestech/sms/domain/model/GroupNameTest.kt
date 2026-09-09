package com.filestech.sms.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** v1.28.3 — la règle unique du nom de groupe, pour les deux entrées (fil et liste). */
class GroupNameTest {

    @Test
    fun `rogne, plafonne a 40 et retire les invisibles`() {
        assertThat(GroupName.normalize("  Famille  ")).isEqualTo("Famille")
        assertThat(GroupName.normalize("a".repeat(60))).hasLength(40)
        assertThat(GroupName.normalize("Fam\u202Eille")).isEqualTo("Famille")
    }

    /** Vide, c'est « pas de nom » : retirer le nom n'est pas un cas à part. */
    @Test
    fun `vide ou blanc rend null`() {
        assertThat(GroupName.normalize(null)).isNull()
        assertThat(GroupName.normalize("")).isNull()
        assertThat(GroupName.normalize("   ")).isNull()
    }
}
