package com.filestech.sms.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * 🔴 v1.27.2 (audit Codex du 2026-08-05, C-01 / C-02) — **le bouton d'urgence n'avait AUCUN test.**
 *
 * # Ce qui est réellement en jeu
 *
 * Un déclenchement envoie de **vrais SMS** aux proches de quelqu'un, et partage sa position. Les
 * deux erreurs possibles ne se valent pas, et elles tirent en sens opposés :
 *
 *  - **déclencher à tort** alerte des proches pour un geste de défilement ; répété, il apprend aux
 *    contacts à ignorer l'alerte — ce qui la détruit le jour où elle est vraie ;
 *  - **ne pas déclencher** laisse quelqu'un le doigt appuyé sur un bouton qui ne fera rien, au pire
 *    moment possible.
 *
 * Ces tests tiennent les **deux bords**. C'est pour ça qu'ils exercent aussi bien le maintien
 * légitime que les six façons de l'annuler.
 *
 * # Historique des deux défauts qu'ils ferment
 *
 *  - **C-01** : passé la fenêtre de discrimination, plus aucune garde de déplacement. Un doigt
 *    posé, immobile 300 ms, puis glissant de 60 à 80 dp **sans quitter le disque** déclenchait
 *    l'alerte trois secondes plus tard. `consume()` avait supprimé le défilement — le symptôme
 *    visible — sans fermer le faux déclenchement.
 *  - **C-02** : la boucle relisait « le premier pointeur appuyé » à chaque évènement sans vérifier
 *    l'identifiant. Un second doigt posé pendant le maintien devenait propriétaire quand le
 *    premier se relevait, alors que le minuteur courait depuis le DOWN d'origine.
 *
 * ⚠️ **Aucun SMS n'est envoyé** : c'est le composant seul qui est monté, avec un compteur à la
 * place de `onTrigger`. Aucun ViewModel, aucune couche téléphonie.
 */
class EmergencyHoldButtonTest {

    @get:Rule val compose = createComposeRule()

    private companion object {
        const val TAG = "urgence"
        const val HOLD_MS = 600L
        val SIZE = 200.dp
    }

    private var triggers = 0

    /**
     * Monte le bouton **dans une colonne défilante**, comme dans l'écran réel : l'arbitrage
     * enfant/parent est précisément ce qui est en cause.
     */
    private fun setUp(enabled: Boolean = true) {
        triggers = 0
        compose.setContent {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(400.dp))
                EmergencyHoldButton(
                    onTrigger = { triggers++ },
                    enabled = enabled,
                    modifier = Modifier.testTag(TAG),
                    holdDurationMs = HOLD_MS,
                    size = SIZE,
                )
                Spacer(Modifier.height(1_200.dp))
            }
        }
    }

    /**
     * ⚠️ **Deux horloges, et il faut faire avancer les DEUX.**
     *
     * `mainClock.advanceTimeBy` fait avancer l'horloge des frames — donc le `delay` du
     * `LaunchedEffect`. Mais l'horodatage des évènements tactiles injectés, celui que le composant
     * lit dans `uptimeMillis`, n'avance QUE par `advanceEventTime`, **à l'intérieur** d'un bloc
     * `performTouchInput`.
     *
     * Ne faire avancer que la première laisse `uptimeMillis - downTime` à zéro : le composant se
     * croit en permanence dans sa fenêtre de discrimination, et toute sa logique temporelle est
     * court-circuitée. Ma première version de ces tests faisait exactement ça — elle rendait cinq
     * échecs, dont « relacher avant la fin déclenche », qui contredit le comportement observé sur
     * appareil depuis des mois. C'était le harnais, pas le produit.
     */
    private fun laisserExpirer() {
        compose.mainClock.advanceTimeBy(HOLD_MS * 2)
        compose.waitForIdle()
    }

    // ──────────────────────── Le maintien légitime DOIT marcher ────────────────────────

    @Test fun un_maintien_immobile_declenche_exactement_une_fois() {
        setUp()
        compose.onNodeWithTag(TAG).performTouchInput {
            down(center)
            advanceEventTime(HOLD_MS + 200)
            moveTo(center)
        }
        laisserExpirer()
        compose.onNodeWithTag(TAG).performTouchInput { up() }

        assertThat(triggers).isEqualTo(1)
    }

    @Test fun un_relachement_avant_la_fin_ne_declenche_pas() {
        setUp()
        compose.onNodeWithTag(TAG).performTouchInput {
            down(center)
            advanceEventTime(HOLD_MS / 2)
            up()
        }
        laisserExpirer()

        assertThat(triggers).isEqualTo(0)
    }

    /**
     * Une **légère** dérive après la fenêtre de discrimination ne doit PAS annuler : c'est le
     * tremblement de main, et l'annuler laisserait quelqu'un appuyer en vain sur un bouton mort.
     */
    @Test fun une_legere_derive_apres_la_fenetre_ne_annule_pas_le_maintien() {
        setUp()
        compose.onNodeWithTag(TAG).performTouchInput {
            down(center)
            advanceEventTime(350)
            moveTo(center + androidx.compose.ui.geometry.Offset(0f, 8.dp.toPx()))
            advanceEventTime(HOLD_MS)
        }
        laisserExpirer()
        compose.onNodeWithTag(TAG).performTouchInput { up() }

        assertThat(triggers).isEqualTo(1)
    }

    // ──────────────────────── Les six façons d'annuler ────────────────────────

    /** Un glissement franc **pendant** la fenêtre : c'est un défilement, pas un appui. */
    @Test fun un_glissement_avant_300ms_ne_declenche_pas() {
        setUp()
        compose.onNodeWithTag(TAG).performTouchInput {
            down(center)
            advanceEventTime(50)
            moveTo(center + androidx.compose.ui.geometry.Offset(0f, -80.dp.toPx()))
            advanceEventTime(HOLD_MS)
        }
        laisserExpirer()
        compose.onNodeWithTag(TAG).performTouchInput { up() }

        assertThat(triggers).isEqualTo(0)
    }

    /**
     * 🔴 **C-01, le cœur du constat.** Doigt posé, immobile plus de 300 ms, **puis** glissement
     * lent de 60 dp — bien au-delà du seuil de dérive, mais toujours dans le disque de 100 dp de
     * rayon. L'ancienne version déclenchait.
     */
    @Test fun un_glissement_lent_apres_300ms_dans_le_disque_ne_declenche_pas() {
        setUp()
        compose.onNodeWithTag(TAG).performTouchInput {
            down(center)
            advanceEventTime(350)
            moveTo(center + androidx.compose.ui.geometry.Offset(0f, 20.dp.toPx()))
            advanceEventTime(100)
            moveTo(center + androidx.compose.ui.geometry.Offset(0f, 40.dp.toPx()))
            advanceEventTime(100)
            moveTo(center + androidx.compose.ui.geometry.Offset(0f, 60.dp.toPx()))
            advanceEventTime(HOLD_MS)
        }
        laisserExpirer()
        compose.onNodeWithTag(TAG).performTouchInput { up() }

        assertThat(triggers).isEqualTo(0)
    }

    /**
     * Les bornes sont le **disque** visible, pas le carré englobant : une dérive diagonale vers un
     * coin a quitté le bouton à l'écran, même si elle reste dans la zone de pointeur.
     */
    @Test fun une_derive_diagonale_hors_du_disque_ne_declenche_pas() {
        setUp()
        compose.onNodeWithTag(TAG).performTouchInput {
            down(center)
            advanceEventTime(350)
            // Coin inférieur droit du carré : hors du cercle inscrit.
            moveTo(bottomRight + androidx.compose.ui.geometry.Offset(-1f, -1f))
            advanceEventTime(HOLD_MS)
        }
        laisserExpirer()
        compose.onNodeWithTag(TAG).performTouchInput { up() }

        assertThat(triggers).isEqualTo(0)
    }

    /**
     * 🔴 **C-02.** Le doigt A démarre le maintien, B se pose, A se relève. Le minuteur court
     * toujours depuis le DOWN de A : sans le suivi d'identifiant, B en héritait et l'alerte
     * partait alors qu'**aucun doigt n'avait tenu la durée complète**.
     */
    @Test fun un_second_doigt_ne_reprend_pas_le_maintien_du_premier() {
        setUp()
        compose.onNodeWithTag(TAG).performTouchInput {
            down(pointerId = 0, position = center)
            advanceEventTime(HOLD_MS / 3)
            down(pointerId = 1, position = center + androidx.compose.ui.geometry.Offset(10f, 10f))
            advanceEventTime(10)
            up(pointerId = 0)
            advanceEventTime(HOLD_MS)
        }
        laisserExpirer()
        compose.onNodeWithTag(TAG).performTouchInput { up(pointerId = 1) }

        assertThat(triggers).isEqualTo(0)
    }

    @Test fun un_bouton_desactive_ne_declenche_jamais() {
        setUp(enabled = false)
        compose.onNodeWithTag(TAG).performTouchInput {
            down(center)
            advanceEventTime(HOLD_MS + 200)
            moveTo(center)
        }
        laisserExpirer()
        compose.onNodeWithTag(TAG).performTouchInput { up() }

        assertThat(triggers).isEqualTo(0)
    }

    /**
     * Sortir du bouton puis y revenir : le maintien a été annulé et **ne se réarme pas** sur le
     * même geste. Sans le drainage jusqu'au UP, le retour relançait un cycle complet.
     */
    @Test fun sortir_puis_revenir_ne_rearme_pas_le_maintien() {
        setUp()
        compose.onNodeWithTag(TAG).performTouchInput {
            down(center)
            advanceEventTime(350)
            moveTo(center + androidx.compose.ui.geometry.Offset(0f, -300.dp.toPx()))
            advanceEventTime(50)
            moveTo(center)
            advanceEventTime(HOLD_MS)
        }
        laisserExpirer()
        compose.onNodeWithTag(TAG).performTouchInput { up() }

        assertThat(triggers).isEqualTo(0)
    }
}
