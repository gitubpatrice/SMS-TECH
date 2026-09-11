package com.filestech.sms.security

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * v1.28.4 (F06) — **un tap sur « Confirme que tu vas bien » ne désarme pas un téléphone dont
 * l'application est verrouillée, ni une session leurre.**
 *
 * Le test mesure le seul fait qui compte : tant que l'état n'est pas réellement ouvert, l'attente
 * **ne se termine pas** — donc rien n'est écrit. Puis qu'elle se termine bien sur `Unlocked`, sans
 * quoi ce test serait un test qui ne peut pas échouer.
 *
 * v1.28.5 (sixième note d'Andrew, point 5) — le geste de DÉSARMEMENT est **jeté** sur `LockedOut`
 * et `PanicDecoy`, et seulement retenu sur `Locked`. Le jumeau de remise à zéro continue
 * d'attendre : il ne désarme rien.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SafetyCallResetGateTest {

    @Test
    fun `verrouille, bloque et leurre retiennent la remise a zero`() = runTest {
        val etat = MutableStateFlow<AppLockManager.LockState>(AppLockManager.LockState.Locked)
        var passe = false
        val attente = launch {
            SafetyCallResetGate.attendreOuverture(etat)
            passe = true
        }

        advanceUntilIdle()
        assertThat(passe).isFalse()

        etat.value = AppLockManager.LockState.LockedOut(until = Long.MAX_VALUE)
        advanceUntilIdle()
        assertThat(passe).isFalse()

        etat.value = AppLockManager.LockState.PanicDecoy
        advanceUntilIdle()
        assertThat(passe).isFalse()

        attente.cancel()
    }

    /** Contrôle POSITIF : l'ouverture réelle libère le geste — sinon le test ci-dessus ne prouve rien. */
    @Test
    fun `l'ouverture reelle libere la remise a zero`() = runTest {
        val etat = MutableStateFlow<AppLockManager.LockState>(AppLockManager.LockState.PanicDecoy)
        var passe = false
        launch {
            SafetyCallResetGate.attendreOuverture(etat)
            passe = true
        }
        advanceUntilIdle()
        assertThat(passe).isFalse()

        etat.value = AppLockManager.LockState.Unlocked
        advanceUntilIdle()

        assertThat(passe).isTrue()
    }

    @Test
    fun `sans verrou configure le geste passe`() {
        assertThat(SafetyCallResetGate.ouvre(AppLockManager.LockState.Disabled)).isTrue()
        assertThat(SafetyCallResetGate.ouvre(AppLockManager.LockState.Locked)).isFalse()
    }

    // ───────────── v1.28.5 — le geste de désarmement : retenu sur Locked, jeté sinon ─────────────

    /**
     * Le cas d'Andrew : geste reçu en session leurre, puis vrai déverrouillage dans la même
     * session de premier plan. Avant, l'attente se terminait sur `Unlocked` et le geste
     * désarmait — un désarmement que l'utilisateur n'a pas fait, décidé sous contrainte.
     */
    @Test
    fun `un geste recu en session leurre est jete, meme si l'on deverrouille ensuite`() = runTest {
        val etat = MutableStateFlow<AppLockManager.LockState>(AppLockManager.LockState.PanicDecoy)
        var verdict: Boolean? = null
        launch { verdict = SafetyCallResetGate.attendreOuvertureOuJeter(etat) }

        advanceUntilIdle()
        assertThat(verdict).isFalse()

        // Trop tard : le verdict est rendu, l'ouverture réelle ne le change pas.
        etat.value = AppLockManager.LockState.Unlocked
        advanceUntilIdle()
        assertThat(verdict).isFalse()
    }

    @Test
    fun `un geste recu pendant un blocage est jete`() = runTest {
        val bloque = AppLockManager.LockState.LockedOut(until = Long.MAX_VALUE)
        val etat = MutableStateFlow<AppLockManager.LockState>(bloque)
        var verdict: Boolean? = null
        launch { verdict = SafetyCallResetGate.attendreOuvertureOuJeter(etat) }

        advanceUntilIdle()

        assertThat(verdict).isFalse()
    }

    /** Le cas légitime : application verrouillée, l'utilisateur saisit son code, le geste s'exécute. */
    @Test
    fun `un geste recu application verrouillee attend le code puis passe`() = runTest {
        val etat = MutableStateFlow<AppLockManager.LockState>(AppLockManager.LockState.Locked)
        var verdict: Boolean? = null
        launch { verdict = SafetyCallResetGate.attendreOuvertureOuJeter(etat) }

        advanceUntilIdle()
        assertThat(verdict).isNull()

        etat.value = AppLockManager.LockState.Unlocked
        advanceUntilIdle()
        assertThat(verdict).isTrue()
    }

    /** Verrouillée puis code leurre : le geste retenu sur `Locked` est jeté au passage en leurre. */
    @Test
    fun `un geste retenu sur verrouille est jete si le code leurre est saisi`() = runTest {
        val etat = MutableStateFlow<AppLockManager.LockState>(AppLockManager.LockState.Locked)
        var verdict: Boolean? = null
        launch { verdict = SafetyCallResetGate.attendreOuvertureOuJeter(etat) }

        advanceUntilIdle()
        etat.value = AppLockManager.LockState.PanicDecoy
        advanceUntilIdle()

        assertThat(verdict).isFalse()
    }

    /** Sans verrou configuré, le geste passe tout de suite. */
    @Test
    fun `sans verrou le geste de desarmement passe`() = runTest {
        val etat = MutableStateFlow<AppLockManager.LockState>(AppLockManager.LockState.Disabled)

        assertThat(SafetyCallResetGate.attendreOuvertureOuJeter(etat)).isTrue()
    }
}
