package com.filestech.sms.data.local.datastore

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * v1.26.1 (audit F12) — couverture de `LockoutSnapshot.isLockoutActive`.
 *
 * **Pourquoi ce fichier existe.** L'audit a constaté que les deux seuls tests d'`AppLockManager`
 * portaient sur `backoffMillis` — un `coerceIn` dans un tableau de six constantes, qui ne peut pas
 * régresser sans qu'on édite le tableau. La SORTIE DU BLOCAGE, elle, n'était testée nulle part :
 * ni `isLockoutActive`, ni `refreshLockoutIfExpired`. C'est précisément le défaut de la v1.26.0
 * — « le blocage ne se levait jamais » — qui est donc resté vert de bout en bout.
 *
 * `isLockoutActive` est une fonction PURE : elle se teste sans appareil, sans Room et sans
 * DataStore. Il n'y avait aucune raison qu'elle ne le soit pas.
 *
 * Les deux horloges qu'elle croise :
 *  - **murale** (`nowMs`) — manipulable par l'utilisateur dans les réglages système ;
 *  - **monotone** (`nowElapsed`, `elapsedRealtime`) — non manipulable, mais remise à zéro par un
 *    redémarrage.
 *
 * La règle est un OU : il faut que les DEUX soient expirées pour libérer. Ces tests verrouillent
 * ce contrat, y compris la tentative d'assouplissement qui a été essayée puis annulée pendant
 * l'audit (faire de la monotone l'autorité ouvrait un contournement par recul d'horloge).
 */
class LockoutSnapshotTest {

    private fun snapshot(
        untilWall: Long = 0L,
        setAtElapsed: Long = 0L,
        durationMs: Long = 0L,
    ) = SecurityStore.LockoutSnapshot(untilWall, setAtElapsed, durationMs)

    @Test
    @DisplayName("aucun blocage enregistre : jamais actif")
    fun `pas de lockout`() {
        assertThat(snapshot().isLockoutActive(nowMs = NOW, nowElapsed = ELAPSED)).isFalse()
    }

    @Test
    @DisplayName("blocage en cours : les deux horloges disent 'pas encore expire'")
    fun `lockout en cours`() {
        val s = snapshot(
            untilWall = NOW + 60_000L,
            setAtElapsed = ELAPSED - 10_000L,
            durationMs = 60_000L,
        )
        assertThat(s.isLockoutActive(NOW, ELAPSED)).isTrue()
    }

    @Test
    @DisplayName("les deux horloges expirees : le blocage se leve — le defaut v1.26.0")
    fun `lockout expire des deux cotes`() {
        val s = snapshot(
            untilWall = NOW - 1_000L,
            setAtElapsed = ELAPSED - 120_000L,
            durationMs = 60_000L,
        )
        assertThat(s.isLockoutActive(NOW, ELAPSED)).isFalse()
    }

    @Test
    @DisplayName("horloge murale AVANCEE : la monotone maintient le blocage (audit R7)")
    fun `avance d horloge ne libere pas`() {
        // L'utilisateur avance la date systeme de plusieurs jours pour « passer » la temporisation.
        val s = snapshot(
            untilWall = NOW - 5 * 86_400_000L, // murale : largement expire
            setAtElapsed = ELAPSED - 10_000L, // monotone : 10 s sur 60 s ecoulees
            durationMs = 60_000L,
        )
        assertThat(s.isLockoutActive(NOW, ELAPSED)).isTrue()
    }

    @Test
    @DisplayName("horloge murale RECULEE apres redemarrage : le blocage tient")
    fun `recul d horloge apres reboot ne libere pas`() {
        // Redemarrage : `elapsedRealtime` repart de zero, donc `nowElapsed < setAtElapsed` et la
        // branche monotone devient inexploitable. L'attaquant recule alors la date systeme.
        //
        // ⚠️ C'est le scenario qui a fait ANNULER une premiere version du correctif M2 : elle
        // bornait la murale a 24 h en lecture, si bien qu'une date reculee de plusieurs annees
        // faisait sauter le blocage sans avoir a viser aucune valeur precise. Ce test verrouille
        // le comportement strict.
        val s = snapshot(
            untilWall = NOW + 60_000L, // echeance ecrite avant le recul d'horloge
            setAtElapsed = 900_000L, // baseline monotone d'avant reboot
            durationMs = 60_000L,
        )
        val afterReboot = 1_000L // elapsedRealtime repart de ~0
        val clockMovedBack = NOW - 5 * 365 * 86_400_000L
        assertThat(s.isLockoutActive(nowMs = clockMovedBack, nowElapsed = afterReboot)).isTrue()
    }

    @Test
    @DisplayName("redemarrage sans manipulation : la murale prend le relais")
    fun `apres reboot la murale fait foi`() {
        val s = snapshot(
            untilWall = NOW + 30_000L,
            setAtElapsed = 900_000L,
            durationMs = 60_000L,
        )
        // Encore dans la fenetre selon la murale.
        assertThat(s.isLockoutActive(nowMs = NOW, nowElapsed = 1_000L)).isTrue()
        // Passee l'echeance murale, plus rien ne retient.
        assertThat(s.isLockoutActive(nowMs = NOW + 31_000L, nowElapsed = 1_000L)).isFalse()
    }

    @Test
    @DisplayName("duree nulle : la branche monotone ne peut pas bloquer a elle seule")
    fun `duree nulle`() {
        val s = snapshot(untilWall = 0L, setAtElapsed = ELAPSED - 1_000L, durationMs = 0L)
        assertThat(s.isLockoutActive(NOW, ELAPSED)).isFalse()
    }

    private companion object {
        /** Horodatage mural arbitraire mais realiste (ms). */
        const val NOW = 1_800_000_000_000L

        /** `elapsedRealtime` arbitraire, largement superieur aux durees testees. */
        const val ELAPSED = 5_000_000L
    }
}
