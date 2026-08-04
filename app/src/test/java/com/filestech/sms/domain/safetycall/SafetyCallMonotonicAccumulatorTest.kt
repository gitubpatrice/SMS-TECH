package com.filestech.sms.domain.safetycall

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.27.2 (audit externe Gemini 2026-08-04) — verrouille le comportement du compteur monotone
 * du deadman face aux redémarrages.
 *
 * Le défaut fermé : `SystemClock.elapsedRealtime()` repart de zéro à chaque redémarrage, la
 * récupération de dérive re-calait l'ancre sur cette valeur, et comme `isExpired` exige que les
 * DEUX horloges aient expiré, redémarrer plus souvent que le délai empêchait le deadman de
 * partir — indéfiniment. Une simple mise à jour système repoussait déjà l'alerte d'autant.
 *
 * ⚠️ Ces tests gardent une fonction qui envoie de VRAIS SMS à de VRAIS contacts. Les deux sens
 * comptent donc autant l'un que l'autre : il doit partir quand il le faut (le défaut corrigé),
 * et surtout **jamais en avance** (le risque introduit par le correctif).
 */
class SafetyCallMonotonicAccumulatorTest {

    private companion object {
        const val TIMEOUT = 24L * 60 * 60 * 1000 // 24 h
        const val HOUR = 60L * 60 * 1000
        const val WALL_START = 1_800_000_000_000L

        /**
         * Ancre monotone juste après un redémarrage : `elapsedRealtime()` au moment où
         * l'application démarre, soit quelques secondes d'uptime — jamais exactement `0L`.
         *
         * ⚠️ Ne PAS utiliser `0L` ici. `isExpired` traite `monotonicLastActivityAt == 0L` comme
         * « config héritée jamais initialisée » et rend `false` d'emblée : les cas de
         * déclenchement passeraient alors pour de mauvaises raisons, et les cas de
         * non-déclenchement seraient VACANTS — verts sans rien exercer.
         */
        const val BOOT_ANCHOR = 5_000L
    }

    private fun cfg(
        monotonicLastActivityAt: Long,
        monotonicAccumulatedMs: Long = 0L,
        lastActivityAt: Long = WALL_START,
        enabled: Boolean = true,
    ) = SafetyCallConfig(
        enabled = enabled,
        timeoutMs = TIMEOUT,
        lastActivityAt = lastActivityAt,
        monotonicLastActivityAt = monotonicLastActivityAt,
        monotonicAccumulatedMs = monotonicAccumulatedMs,
        contacts = listOf(SafetyCallContact(displayName = "Proche", phoneNumber = "0612345678")),
    )

    /** Horloge murale largement expirée : isole le comportement de l'horloge monotone. */
    private fun wallLongExpired() = WALL_START + TIMEOUT * 10

    // ──────────── Le défaut corrigé ────────────

    @Test fun `deadman fires after a reboot once the banked time covers the timeout`() {
        // 23 h capitalisées avant le redémarrage, puis l'appareil redémarre : la récupération de
        // dérive re-cale l'ancre sur l'uptime courant, le capital est CONSERVÉ. Une heure
        // d'uptime plus tard, le total atteint 24 h.
        val afterReboot =
            cfg(monotonicLastActivityAt = BOOT_ANCHOR, monotonicAccumulatedMs = 23 * HOUR)

        assertThat(afterReboot.monoElapsedMs(nowMonoMs = BOOT_ANCHOR + HOUR)).isEqualTo(TIMEOUT)
        assertThat(
            afterReboot.isExpired(nowMs = wallLongExpired(), nowMonoMs = BOOT_ANCHOR + HOUR),
        ).isTrue()
    }

    @Test fun `repeated reboots no longer postpone the deadman indefinitely`() {
        // Le scénario d'attaque : redémarrer toutes les 6 h. Avant le correctif, chaque
        // redémarrage remettait le compteur à zéro et 24 h d'uptime consécutif n'étaient jamais
        // atteintes. Avec le capital, quatre tranches de 6 h suffisent.
        var banked = 0L
        repeat(4) { banked += 6 * HOUR } // 4 sessions de 6 h entrecoupées de redémarrages
        val afterFourthReboot =
            cfg(monotonicLastActivityAt = BOOT_ANCHOR, monotonicAccumulatedMs = banked)

        assertThat(
            afterFourthReboot.isExpired(nowMs = wallLongExpired(), nowMonoMs = BOOT_ANCHOR),
        ).isTrue()
    }

    // ──────────── Le risque introduit : partir EN AVANCE ────────────

    @Test fun `does not fire before the timeout even with banked time`() {
        val almost =
            cfg(monotonicLastActivityAt = BOOT_ANCHOR, monotonicAccumulatedMs = 23 * HOUR)

        assertThat(
            almost.isExpired(nowMs = wallLongExpired(), nowMonoMs = BOOT_ANCHOR + HOUR - 1),
        ).isFalse()
    }

    @Test fun `an advanced wall clock alone still cannot fire it`() {
        // Protection SEC-11 intacte : l'horloge murale a beau être poussée très loin, le
        // compteur monotone n'a pas atteint le délai.
        val fresh = cfg(monotonicLastActivityAt = BOOT_ANCHOR, monotonicAccumulatedMs = 0L)

        assertThat(
            fresh.isExpired(nowMs = wallLongExpired(), nowMonoMs = BOOT_ANCHOR + HOUR),
        ).isFalse()
    }

    @Test fun `a reset clears the banked time`() {
        // Ce que tout site de reset doit produire : les trois champs repartent ensemble.
        val afterReset = cfg(monotonicLastActivityAt = 5 * HOUR, monotonicAccumulatedMs = 0L)
            .copy(lastActivityAt = WALL_START)

        assertThat(afterReset.monoElapsedMs(nowMonoMs = 5 * HOUR)).isEqualTo(0L)
        assertThat(afterReset.isExpired(nowMs = wallLongExpired(), nowMonoMs = 5 * HOUR)).isFalse()
    }

    // ──────────── La fenêtre entre le redémarrage et la récupération de dérive ────────────

    @Test fun `a not-yet-realigned anchor never subtracts from the banked time`() {
        // Juste après un redémarrage et AVANT le passage de la récupération de dérive, l'ancre
        // persistée (d'avant-redémarrage) est supérieure à `nowMono`. Le segment courant est
        // donc négatif : il doit être ignoré, jamais retranché — sinon le deadman reculerait.
        val notYetRealigned = cfg(
            monotonicLastActivityAt = 100 * HOUR, // valeur d'avant redémarrage
            monotonicAccumulatedMs = 23 * HOUR,
        )

        assertThat(notYetRealigned.monoElapsedMs(nowMonoMs = HOUR)).isEqualTo(23 * HOUR)
    }

    // ──────────── Migration depuis une config antérieure ────────────

    @Test fun `a legacy config without banked time behaves exactly as before`() {
        // `monotonicAccumulatedMs` absent du stockage ⇒ 0L : le calcul se réduit au segment
        // courant, soit très exactement la formule d'avant le correctif. Aucun saut au premier
        // démarrage suivant la mise à jour.
        val legacy = cfg(monotonicLastActivityAt = 2 * HOUR, monotonicAccumulatedMs = 0L)

        assertThat(legacy.monoElapsedMs(nowMonoMs = 2 * HOUR + TIMEOUT)).isEqualTo(TIMEOUT)
        assertThat(legacy.isExpired(nowMs = wallLongExpired(), nowMonoMs = 2 * HOUR + TIMEOUT))
            .isTrue()
    }

    @Test fun `an uninitialised monotonic anchor never fires`() {
        // Filet de sécurité v1.10.0 conservé : une config héritée sans instantané monotone ne
        // déclenche pas, quoi qu'affiche l'horloge murale.
        val uninitialised = cfg(monotonicLastActivityAt = 0L).copy(monotonicLastActivityAt = 0L)

        assertThat(uninitialised.isExpired(nowMs = wallLongExpired(), nowMonoMs = TIMEOUT * 10))
            .isFalse()
    }

    // ──────────── Cohérence avertissement / déclenchement ────────────

    @Test fun `the warning window uses the same counter as the trigger`() {
        // 19 h capitalisées : dans la fenêtre des 6 h précédant l'expiration (24 h), pas encore
        // expiré. Si les deux méthodes ne partageaient pas le même compteur, la notification
        // « confirme que tu vas bien » se décalerait de l'alerte qu'elle annonce.
        val inWindow =
            cfg(monotonicLastActivityAt = BOOT_ANCHOR, monotonicAccumulatedMs = 19 * HOUR)
        val wallInWindow = WALL_START + 19 * HOUR

        assertThat(
            inWindow.isInWarningWindow(nowMs = wallInWindow, nowMonoMs = BOOT_ANCHOR),
        ).isTrue()
        assertThat(inWindow.isExpired(nowMs = wallInWindow, nowMonoMs = BOOT_ANCHOR)).isFalse()
    }

    @Test fun `disabled config never fires whatever the banked time`() {
        val off = cfg(
            monotonicLastActivityAt = BOOT_ANCHOR,
            monotonicAccumulatedMs = TIMEOUT * 10,
            enabled = false,
        )

        assertThat(off.isExpired(nowMs = wallLongExpired(), nowMonoMs = TIMEOUT * 10)).isFalse()
    }
}
