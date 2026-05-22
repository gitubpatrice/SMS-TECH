package com.filestech.sms

import com.filestech.sms.ui.screens.conversations.ConversationsViewModel
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.13.0 — garde-régression dédiée aux nouveautés de la release :
 *  - Sujet A : multi-sélection conv → coffre. Vérifie la logique du
 *    `selectionMode` derived dans [ConversationsViewModel.UiState] : la
 *    transition vide ↔ non-vide doit refléter strictement le booléen exposé
 *    à l'UI (TopAppBar contextuelle).
 *  - Sujet B : PIN/pass distinct coffre. Le hash/verify est testé en
 *    instrumented (DataStore + Android Keystore), trop d'I/O pour un unit
 *    test pur JVM. On garde ici la garde haut-niveau "selection backend".
 *  - Sujet C : partage + envoi depuis conv vault. La voie d'envoi
 *    (`SendSmsUseCase` → `ConversationMirror.ensureConversation`) ne touche
 *    plus à `in_vault` depuis v1.11.0 ; pas de test JVM faisable sans DB,
 *    référence à l'audit recon dans le commit message.
 */
class AuditV1130Test {

    // ──────────────── Sujet A — UiState.selectionMode ────────────────

    @Test fun `selectionMode is false when selectedIds is empty`() {
        val state = ConversationsViewModel.UiState(selectedIds = emptySet())
        assertThat(state.selectionMode).isFalse()
    }

    @Test fun `selectionMode is true when selectedIds has one item`() {
        val state = ConversationsViewModel.UiState(selectedIds = setOf(42L))
        assertThat(state.selectionMode).isTrue()
    }

    @Test fun `selectionMode is true when selectedIds has multiple items`() {
        val state = ConversationsViewModel.UiState(selectedIds = setOf(1L, 2L, 3L))
        assertThat(state.selectionMode).isTrue()
        assertThat(state.selectedIds).hasSize(3)
    }

    @Test fun `UiState defaults match a fresh installed state`() {
        // Sanity baseline : un user qui ouvre l'app n'a aucune conv
        // sélectionnée, pas en panic decoy.
        val state = ConversationsViewModel.UiState()
        assertThat(state.selectionMode).isFalse()
        assertThat(state.selectedIds).isEmpty()
        assertThat(state.isPanicDecoy).isFalse()
    }
}
