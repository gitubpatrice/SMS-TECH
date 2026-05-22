package com.filestech.sms

import com.filestech.sms.data.local.datastore.EmergencyCallBehavior
import com.filestech.sms.data.local.datastore.SecuritySettings
import com.filestech.sms.ui.screens.conversations.ConversationsViewModel
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.14.0 — garde-régression dédiée aux nouveautés de la release :
 *  - Sujet 1 : auto-lock coffre. Sémantique simple (lock() au onBack), pas
 *    testable en pur JVM sans Compose lifecycle. Couvert par audit code.
 *  - Sujet 2 : EmergencyCallBehavior enum + EmergencyCallHelper number
 *    whitelist (112 / 17 hardcoded). Whitelist testée en instrumented
 *    (besoin Context Android pour les Intent calls), pas en JVM pur.
 *  - Sujet 3 : showIAmOkChip derivation dans UiState — basé sur
 *    `lastTriggeredAt` + fenêtre 30 min + non-PanicDecoy.
 *  - Sujet 4 : EmergencyCallBehavior par défaut = DIALER_ONLY (no perm).
 */
class AuditV1140Test {

    // ──────────── Sujet 3 — UiState defaults & derived ────────────

    @Test fun `UiState showIAmOkChip default false`() {
        val state = ConversationsViewModel.UiState()
        assertThat(state.showIAmOkChip).isFalse()
    }

    @Test fun `UiState selectionMode and showIAmOkChip are independent`() {
        // selection mode et chip "Je vais bien" peuvent coexister
        // techniquement (mais l'UI peut choisir d'afficher l'un ou l'autre).
        val state = ConversationsViewModel.UiState(
            selectedIds = setOf(1L, 2L),
            showIAmOkChip = true,
        )
        assertThat(state.selectionMode).isTrue()
        assertThat(state.showIAmOkChip).isTrue()
    }

    // ──────────── Sujet 4 — defaults safe (no perm needed) ────────────

    @Test fun `SecuritySettings default emergencyCallBehavior is DIALER_ONLY`() {
        val s = SecuritySettings()
        assertThat(s.emergencyCallBehavior).isEqualTo(EmergencyCallBehavior.DIALER_ONLY)
    }

    @Test fun `SecuritySettings default sendIAmOkSmsOnReset is true`() {
        // L'opt-in par défaut est ON : l'user qui appuie "Je vais bien"
        // après une fausse alerte rassure ses contacts par SMS. C'est le
        // comportement attendu par défaut (alignment avec l'intention user
        // de la majorité des cas). Désactivable.
        val s = SecuritySettings()
        assertThat(s.sendIAmOkSmsOnReset).isTrue()
    }

    @Test fun `EmergencyCallBehavior enum has exactly 2 values`() {
        // Garde-régression : si quelqu'un ajoute un NIVEAU 2 (tap unique
        // direct call) sans débat threat-model, ce test casse et force le
        // discussion. v1.14 = 2 niveaux strict (DIALER_ONLY + HOLD_3S).
        assertThat(EmergencyCallBehavior.entries).hasSize(2)
        assertThat(EmergencyCallBehavior.entries).containsExactly(
            EmergencyCallBehavior.DIALER_ONLY,
            EmergencyCallBehavior.HOLD_3S_DIRECT_CALL,
        )
    }
}
