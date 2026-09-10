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
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SafetyCallResetGateTest {

    @Test
    fun `verrouille, bloque et leurre retiennent le geste`() = runTest {
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
    fun `l'ouverture reelle libere le geste`() = runTest {
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
}
