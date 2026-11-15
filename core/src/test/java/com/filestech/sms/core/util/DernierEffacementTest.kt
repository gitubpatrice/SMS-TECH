package com.filestech.sms.core.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * 2026-11-14 — la marque de l'effacement d'urgence, que relit la reprise des MMS reçus : un téléchargement lancé avant
 * un effacement ne ressuscite rien après lui. Sans effacement, rien n'est jamais « antérieur » — sinon tout MMS reçu
 * serait écarté.
 */
class DernierEffacementTest {

    @AfterEach
    fun oublier() {
        DernierEffacement.oublierPourLesTests()
    }

    /** Contrôle négatif : aucun effacement, aucun écartement — même pour une heure inconnue (0). */
    @Test
    fun `sans effacement - rien n'est anterieur`() {
        assertThat(DernierEffacement.anterieur(0L)).isFalse()
        assertThat(DernierEffacement.anterieur(1_000L)).isFalse()
    }

    @Test
    fun `apres un effacement - ce qui a commence avant est anterieur, ce qui commence apres ne l'est pas`() {
        DernierEffacement.marquer(maintenant = 5_000L)

        assertThat(DernierEffacement.anterieur(4_999L)).isTrue()
        assertThat(DernierEffacement.anterieur(5_000L)).isTrue()
        assertThat(DernierEffacement.anterieur(5_001L)).isFalse()
        // Une heure inconnue, après un effacement : dans le doute, on ne ressuscite rien.
        assertThat(DernierEffacement.anterieur(0L)).isTrue()
    }

    /** Une horloge qui recule ne fait pas oublier un effacement plus récent. */
    @Test
    fun `la marque ne recule jamais`() {
        DernierEffacement.marquer(maintenant = 9_000L)
        DernierEffacement.marquer(maintenant = 2_000L)

        assertThat(DernierEffacement.anterieur(8_000L)).isTrue()
    }
}
