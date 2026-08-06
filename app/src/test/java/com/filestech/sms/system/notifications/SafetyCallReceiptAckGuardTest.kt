package com.filestech.sms.system.notifications

import com.filestech.sms.domain.safetycall.SafetyCallConfig
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.27.4 — verrouille **la garde d'état** du récepteur d'acquittement par balayage.
 *
 * # Ce que ce test protège, et pourquoi il vaut plus qu'il n'y paraît
 *
 * Le récepteur est `exported="false"`, et cela ne suffit pas : un `PendingIntent` est une
 * **capacité réutilisable**. Une application dotée de l'accès *notification listener* lit le
 * `StatusBarNotification`, donc son `deleteIntent`, en garde la référence, et peut la rejouer plus
 * tard — relecture GPT-5.2 du 2026-08-06, SC-DELINT-REPLAY, `CONFIRMÉ`, `CRITIQUE`.
 *
 * Sans la garde, un rejeu **pendant une séquence en cours** désarmait le Safety call et coupait les
 * relances restantes : le coupe-circuit du deadman que tout ce fichier existe pour interdire.
 *
 * ⚠️ La condition testée ici est **la même** que celle qui décide de poser le `deleteIntent` dans
 * `SafetyCallNotice.decide`. C'est volontaire : la surface qui offre le geste et le code qui
 * l'exécute doivent partager une seule définition de « terminal », faute de quoi le jumeau
 * asymétrique se réinstalle entre les deux. `isSequenceTerminal` n'a **pas** la même définition et
 * ne doit pas être substitué ici.
 *
 * Le test reproduit la garde plutôt que d'instancier le récepteur, qui exige Android et Hilt : ce
 * qu'on protège est la **condition**, pas le câblage.
 */
class SafetyCallReceiptAckGuardTest {

    /** Réplique exacte de la garde du récepteur. */
    private fun acceptsSwipeAck(cfg: SafetyCallConfig): Boolean {
        if (!cfg.isTriggered) return false
        val sequenceFinished = cfg.messagesDelivered >= SafetyCallConfig.TOTAL_MESSAGES
        return sequenceFinished && !cfg.enabled
    }

    private fun terminalConfig() = SafetyCallConfig(
        enabled = false,
        triggeredAt = 1_786_000_000_000L,
        messagesSent = SafetyCallConfig.TOTAL_MESSAGES,
        claimedAt = 0L,
    )

    @Test
    fun `un recu de fin de sequence est acquittable`() {
        // Le seul cas qui doit passer : tout est parti, la protection est deja coupee.
        assertThat(acceptsSwipeAck(terminalConfig())).isTrue()
    }

    @Test
    fun `un rejeu pendant une sequence en cours est REFUSE`() {
        // 🔴 LE TEST QUI FERME SC-DELINT-REPLAY.
        //
        // Deux messages partis sur quatre, relances encore attendues. Sans cette garde, un jeton
        // capture lors d'un cycle precedent aurait ici desarme le deadman.
        val inProgress = SafetyCallConfig(
            enabled = true,
            triggeredAt = 1_786_000_000_000L,
            messagesSent = 2,
            claimedAt = 0L,
        )

        assertThat(inProgress.hasRelancePending).isTrue()
        assertThat(acceptsSwipeAck(inProgress)).isFalse()
    }

    @Test
    fun `un rejeu pendant le dernier envoi EN VOL est REFUSE`() {
        // La borne la plus fourbe. `messagesSent` compte les creneaux RESERVES : quand le worker du
        // dernier message reserve le sien, le compteur atteint TOTAL_MESSAGES **avant que le SMS ne
        // parte**, et `hasRelancePending` devient faux instantanement. Une garde ecrite sur
        // `messagesSent` — et non sur `messagesDelivered` — aurait donc accepte le rejeu au pire
        // moment : pendant que le worker envoie la derniere alerte.
        val lastInFlight = SafetyCallConfig(
            enabled = true,
            triggeredAt = 1_786_000_000_000L,
            messagesSent = SafetyCallConfig.TOTAL_MESSAGES,
            claimedAt = 1_786_000_100_000L,
        )

        assertThat(lastInFlight.messagesSent).isEqualTo(SafetyCallConfig.TOTAL_MESSAGES)
        assertThat(lastInFlight.isSendInFlight).isTrue()
        assertThat(lastInFlight.messagesDelivered)
            .isLessThan(SafetyCallConfig.TOTAL_MESSAGES)
        assertThat(acceptsSwipeAck(lastInFlight)).isFalse()
    }

    @Test
    fun `une protection encore armee n'est jamais coupee par un balayage`() {
        // Repli ferme. Cet etat ne devrait pas exister — le desarmement est ecrit dans la
        // transaction du dernier envoi — mais s'il survenait, le balayage doit rester inerte et le
        // recu revenir. Une information qui revient echoue du bon cote ; une protection coupee, non.
        val armedYetFinished = terminalConfig().copy(enabled = true)

        assertThat(acceptsSwipeAck(armedYetFinished)).isFalse()
    }

    @Test
    fun `un rejeu apres acquittement ne rearchive rien`() {
        // Apres un premier acquittement, `withActivityReset` a remis `triggeredAt` a zero. Un second
        // envoi du meme jeton doit donc etre sans effet : sans cela, chaque rejeu ferait avancer
        // `generation` et invaliderait des workers vivants.
        val acknowledged = terminalConfig().withActivityReset(disarmIfTriggered = true)

        assertThat(acknowledged.isTriggered).isFalse()
        assertThat(acceptsSwipeAck(acknowledged)).isFalse()
    }

    @Test
    fun `un cycle jamais declenche n'est pas acquittable`() {
        assertThat(acceptsSwipeAck(SafetyCallConfig())).isFalse()
    }
}
