package com.filestech.sms

import com.filestech.sms.domain.emergency.EmergencyConfig
import com.filestech.sms.domain.emergency.EmergencyTemplate
import com.filestech.sms.domain.safetycall.SafetyCallConfig
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.10.0 — garde-régression dédiée aux nouveautés de la release :
 *  - SEC-11 : clock monotonic complémentaire dans [SafetyCallConfig.isExpired] et
 *    [SafetyCallConfig.isInWarningWindow] — défense contre une attaque wall-clock
 *    (root qui avance l'horloge OS pour forcer le trigger prématuré).
 *
 * Les tests existants Safety call de [AuditV190Test] couvrent le cas nominal
 * (les 2 clocks alignées). Ici on cible les écarts entre les deux et les
 * migrations.
 */
class AuditV1100Test {

    // ──────────────── SEC-11 — clock-forward attack ────────────────

    @Test fun `isExpired returns false when wall is forwarded but mono not yet expired (SEC-11)`() {
        // Scénario : un attaquant root avance la wall-clock OS de +25h alors
        // que seulement 1h s'est réellement écoulée depuis l'arming. Pré-SEC-11,
        // `isExpired` retournait `true` et le SMS partait. Post-SEC-11, la
        // mono clock dit que seulement 1h s'est écoulée → trigger refusé.
        val armedWall = 1_000_000_000L
        val armedMono = 100_000L
        val timeoutMs = SafetyCallConfig.TIMEOUT_24H_MS
        val c = SafetyCallConfig(
            enabled = true,
            timeoutMs = timeoutMs,
            lastActivityAt = armedWall,
            monotonicLastActivityAt = armedMono,
        )
        val nowWallForwarded = armedWall + timeoutMs + 60_000L // +1 min après expiry wall
        val nowMonoReal = armedMono + (60 * 60 * 1000L)        // 1h réelle écoulée
        assertThat(c.isExpired(nowMs = nowWallForwarded, nowMonoMs = nowMonoReal)).isFalse()
    }

    @Test fun `isExpired returns false when mono is forwarded but wall not yet expired (SEC-11)`() {
        // Scénario symétrique (improbable mais correct par symétrie) : la wall
        // est honnête (1h), un quelconque artefact ferait sauter mono très haut.
        // Les deux conditions doivent matcher pour trigger.
        val armedWall = 1_000_000_000L
        val armedMono = 100_000L
        val timeoutMs = SafetyCallConfig.TIMEOUT_24H_MS
        val c = SafetyCallConfig(
            enabled = true,
            timeoutMs = timeoutMs,
            lastActivityAt = armedWall,
            monotonicLastActivityAt = armedMono,
        )
        val nowWallReal = armedWall + (60 * 60 * 1000L)         // 1h réelle wall
        val nowMonoForwarded = armedMono + timeoutMs + 60_000L  // +1 min après expiry mono
        assertThat(c.isExpired(nowMs = nowWallReal, nowMonoMs = nowMonoForwarded)).isFalse()
    }

    @Test fun `isExpired returns true when both clocks have expired (nominal case)`() {
        val armedWall = 1_000_000_000L
        val armedMono = 100_000L
        val timeoutMs = SafetyCallConfig.TIMEOUT_24H_MS
        val c = SafetyCallConfig(
            enabled = true,
            timeoutMs = timeoutMs,
            lastActivityAt = armedWall,
            monotonicLastActivityAt = armedMono,
        )
        val now = armedWall + timeoutMs + 1L
        val nowMono = armedMono + timeoutMs + 1L
        assertThat(c.isExpired(nowMs = now, nowMonoMs = nowMono)).isTrue()
    }

    // ──────────────── SEC-11 — post-reboot drift ────────────────

    @Test fun `isExpired returns false when monotonic is stale post-reboot (mono goes backward)`() {
        // Scénario : l'user arme à `armedMono=10_000_000_000L`. Reboot. La
        // valeur stockée reste 10_000_000_000L mais `elapsedRealtime` repart
        // de 0 → `nowMono` est petit. La soustraction donne un résultat très
        // NÉGATIF → la condition `>= timeoutMs` est forcément fausse.
        // MainApplication.onCreate doit détecter ce cas et re-poser
        // monotonicLastActivityAt = nowMono (cf. drift recovery).
        val armedWall = 1_000_000_000L
        val armedMonoPreReboot = 10_000_000_000L
        val timeoutMs = SafetyCallConfig.TIMEOUT_24H_MS
        val c = SafetyCallConfig(
            enabled = true,
            timeoutMs = timeoutMs,
            lastActivityAt = armedWall,
            monotonicLastActivityAt = armedMonoPreReboot,
        )
        val nowWallAfter = armedWall + timeoutMs + 1L  // wall a écoulé timeoutMs
        val nowMonoPostReboot = 60_000L                // uptime 1min post-reboot
        // Sans drift recovery, isExpired = false (mono "n'a pas écoulé").
        assertThat(c.isExpired(nowMs = nowWallAfter, nowMonoMs = nowMonoPostReboot)).isFalse()
    }

    // ──────────────── SEC-11 — migration v1.9.0 → v1.10.0 ────────────────

    @Test fun `isExpired returns false when monotonic is 0L (v1_9_0 config migrated)`() {
        // Migration : une config v1.9.0 héritée n'a pas de monotonicLastActivityAt
        // (clé DataStore absente → default 0L). Filet de sécurité : on refuse
        // de trigger tant que mono = 0L, même si wall a expiré. Le premier
        // reset après upgrade pose la valeur correcte et débloque le trigger.
        val now = 10_000_000_000L
        val nowMono = 5_000_000_000L
        val c = SafetyCallConfig(
            enabled = true,
            timeoutMs = SafetyCallConfig.TIMEOUT_24H_MS,
            lastActivityAt = now - SafetyCallConfig.TIMEOUT_24H_MS - 1L,
            monotonicLastActivityAt = 0L, // ← config v1.9.0 héritée
        )
        assertThat(c.isExpired(nowMs = now, nowMonoMs = nowMono)).isFalse()
    }

    @Test fun `isInWarningWindow returns false when monotonic is 0L (v1_9_0 config migrated)`() {
        // Même filet pour la fenêtre de warning : pas de notif "tu vas
        // trigger bientôt" tant que la config n'est pas migrée.
        val now = 10_000_000_000L
        val nowMono = 5_000_000_000L
        val withinWindow = SafetyCallConfig.TIMEOUT_24H_MS - (5 * 60 * 60 * 1000L)
        val c = SafetyCallConfig(
            enabled = true,
            timeoutMs = SafetyCallConfig.TIMEOUT_24H_MS,
            lastActivityAt = now - withinWindow,
            monotonicLastActivityAt = 0L,
        )
        assertThat(c.isInWarningWindow(nowMs = now, nowMonoMs = nowMono)).isFalse()
    }

    // ──────────────── Emergency mode — opt-in defaults ────────────────

    @Test fun `EmergencyConfig defaults are opt-in (disabled, NEED_HELP, includeLocation true)`() {
        val c = EmergencyConfig()
        assertThat(c.enabled).isFalse()
        assertThat(c.template).isEqualTo(EmergencyTemplate.NEED_HELP)
        assertThat(c.includeLocation).isTrue()
        assertThat(c.lastTriggeredAt).isEqualTo(0L)
    }

    @Test fun `EmergencyConfig isInAntiSpamWindow returns false when never triggered`() {
        val c = EmergencyConfig(enabled = true, lastTriggeredAt = 0L)
        assertThat(c.isInAntiSpamWindow(nowMs = 10_000_000_000L, nowMonoMs = 5_000_000_000L))
            .isFalse()
    }

    @Test fun `EmergencyConfig isInAntiSpamWindow returns true within 60s post-trigger`() {
        val now = 10_000_000_000L
        val nowMono = 5_000_000_000L
        val c = EmergencyConfig(
            enabled = true,
            lastTriggeredAt = now - 30_000L,
            monotonicLastTriggeredAt = nowMono - 30_000L,
        )
        assertThat(c.isInAntiSpamWindow(nowMs = now, nowMonoMs = nowMono)).isTrue()
    }

    @Test fun `EmergencyConfig isInAntiSpamWindow returns false after 60s on both clocks`() {
        val now = 10_000_000_000L
        val nowMono = 5_000_000_000L
        val c = EmergencyConfig(
            enabled = true,
            lastTriggeredAt = now - 61_000L,
            monotonicLastTriggeredAt = nowMono - 61_000L,
        )
        assertThat(c.isInAntiSpamWindow(nowMs = now, nowMonoMs = nowMono)).isFalse()
    }

    // ──────────────── S2 — clock-forward attack on anti-spam ────────────────

    @Test fun `isInAntiSpamWindow stays active when wall is forwarded but mono not (S2)`() {
        // Attaque : l'agresseur avance la wall-clock OS de +61s pour faire
        // sortir le cooldown wall-clock. La mono clock dit que seulement
        // 5s se sont écoulées → cooldown TOUJOURS actif.
        val triggeredWall = 1_000_000_000L
        val triggeredMono = 100_000L
        val c = EmergencyConfig(
            enabled = true,
            lastTriggeredAt = triggeredWall,
            monotonicLastTriggeredAt = triggeredMono,
        )
        val wallForwarded = triggeredWall + 61_000L
        val monoReal = triggeredMono + 5_000L
        assertThat(c.isInAntiSpamWindow(nowMs = wallForwarded, nowMonoMs = monoReal)).isTrue()
    }

    @Test fun `isInAntiSpamWindow falls back to wall-only when monotonic is 0L (v1_10_0 migration)`() {
        // Migration : un user qui a déclenché sous une build pré-fix S2
        // a une config avec lastTriggeredAt set mais monotonicLastTriggeredAt=0L.
        // On retombe sur le check wall-clock par compat ascendante.
        val now = 10_000_000_000L
        val nowMono = 5_000_000_000L
        val cActive = EmergencyConfig(
            enabled = true,
            lastTriggeredAt = now - 30_000L,
            monotonicLastTriggeredAt = 0L,
        )
        assertThat(cActive.isInAntiSpamWindow(nowMs = now, nowMonoMs = nowMono)).isTrue()
        val cExpired = EmergencyConfig(
            enabled = true,
            lastTriggeredAt = now - 61_000L,
            monotonicLastTriggeredAt = 0L,
        )
        assertThat(cExpired.isInAntiSpamWindow(nowMs = now, nowMonoMs = nowMono)).isFalse()
    }

    // ──────────────── Emergency templates ────────────────

    @Test fun `EmergencyTemplate enum has 3 values (no CUSTOM in v1_10_0)`() {
        val names = EmergencyTemplate.entries.map { it.name }.toSet()
        assertThat(names).containsExactly("NEED_HELP", "DANGER", "DISCREET")
    }

    /*
     * v1.28.12 — LES GARDES SUR LE TEXTE ONT DÉMÉNAGÉ, ET SE SONT ÉLARGIES.
     *
     * Six tests vivaient ici : le rendu avec et sans position, le libellé validé pour DANGER, le
     * préfixe ⚠️, l'absence de tiret cadratin, le cap à 160 caractères. Ils étaient justes, et
     * ils ne regardaient **qu'une seule langue** — parce qu'il n'y en avait qu'une : les corps de
     * SMS étaient écrits en français dans `EmergencyTemplate` et partaient en français à tout le
     * monde, anglophones compris.
     *
     * Les mêmes garanties sont désormais vérifiées sur les RESSOURCES RÉELLES et **langue par
     * langue**, à travers l'implémentation qui sert aussi à l'envoi :
     * `com.filestech.sms.system.safety.SafetyMessageTextsTest`. Ce fichier-ci garde ce qui relève
     * encore du domaine : la forme de l'énumération.
     */

    // ──────────────── SEC-4 — anti-spam underflow fail-safe ────────────────

    @Test fun `isInAntiSpamWindow stays active when monotonic delta is negative (post-reboot underflow)`() {
        // Post-reboot avant drift recovery async : nowMono est petit (uptime
        // récent) et monotonicLastTriggeredAt grand (valeur pré-reboot
        // persistée). Le delta est négatif → on traite comme "cooldown
        // actif" (fail-safe contre tentative root reboot + clock forward).
        val nowWall = 10_000_000_000L
        val nowMono = 60_000L                       // 1 min après reboot
        val storedMono = 999_999_000_000L            // valeur pré-reboot stale
        val c = EmergencyConfig(
            enabled = true,
            // Wall hors fenêtre (cooldown wall expiré) — c'est seulement
            // grâce au fail-safe mono que le cooldown reste actif.
            lastTriggeredAt = nowWall - 120_000L,
            monotonicLastTriggeredAt = storedMono,
        )
        assertThat(c.isInAntiSpamWindow(nowMs = nowWall, nowMonoMs = nowMono)).isTrue()
    }
}
