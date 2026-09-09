package com.filestech.sms.ui.screens.thread

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.28.3 (audit global, X-03 — mesuré sur le S9) — **deux photos doivent pouvoir partir dans un
 * MMS.** Le plafond se partage ; le refus ne vient que quand la part de chacune tombe sous le
 * seuil de lisibilité.
 */
class MmsAttachmentBudgetTest {

    private val plafond = 280L * 1024L

    @Test
    fun `deux photos se partagent le plafond a parts egales`() {
        assertThat(MmsAttachmentBudget.partParImage(plafond, texteBytes = 0L, autresBytes = 0L, nombreImages = 2))
            .isEqualTo(140L * 1024L)
    }

    /** Le texte et les pièces non compressibles se servent d'abord ; les images se partagent le reste. */
    @Test
    fun `le texte et un vocal sont deduits avant le partage`() {
        val part = MmsAttachmentBudget.partParImage(
            plafond,
            texteBytes = 20L * 1024L,
            autresBytes = 60L * 1024L,
            nombreImages = 2,
        )
        assertThat(part).isEqualTo(100L * 1024L)
    }

    /** Contrôle POSITIF du refus : trop d'images pour que chacune reste lisible → `null`. */
    @Test
    fun `huit photos ne tiennent pas et le refus est explicite`() {
        assertThat(MmsAttachmentBudget.partParImage(plafond, 0L, 0L, nombreImages = 8)).isNull()
        // Et juste au-dessus du seuil, ça passe : 5 × 56 Ko.
        assertThat(MmsAttachmentBudget.partParImage(plafond, 0L, 0L, nombreImages = 5)).isEqualTo(56L * 1024L)
    }

    @Test
    fun `sans image ou sans place il n'y a pas de part`() {
        assertThat(MmsAttachmentBudget.partParImage(plafond, 0L, 0L, nombreImages = 0)).isNull()
        val sansPlace = MmsAttachmentBudget.partParImage(
            plafond,
            texteBytes = plafond,
            autresBytes = 0L,
            nombreImages = 1,
        )
        assertThat(sansPlace).isNull()
    }
}
