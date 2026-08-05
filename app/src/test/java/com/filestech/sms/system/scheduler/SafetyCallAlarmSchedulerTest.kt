package com.filestech.sms.system.scheduler

import com.filestech.sms.domain.safetycall.SafetyCallConfig
import com.filestech.sms.domain.safetycall.SafetyCallContact
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.27.2 — verrouille **quand** le Safety call se réveille.
 *
 * La décision entière tient dans [SafetyCallAlarmScheduler.nextWakeUpAt], volontairement pure pour
 * être testable sans appareil. Une erreur ici ne se voit pas : elle produit un deadman qui part
 * trop tôt, ou — bien pire — qui ne part pas.
 */
class SafetyCallAlarmSchedulerTest {

    private companion object {
        const val ARMED_AT = 1_700_000_000_000L
        const val TIMEOUT = SafetyCallConfig.TIMEOUT_24H_MS
    }

    private fun armed() = SafetyCallConfig(
        enabled = true,
        timeoutMs = TIMEOUT,
        lastActivityAt = ARMED_AT,
        monotonicLastActivityAt = 1L,
        contacts = listOf(SafetyCallContact(phoneNumber = "+33611111111")),
    )

    @Test
    fun `un deadman arme se reveille a l echeance, pas avant`() {
        assertThat(SafetyCallAlarmScheduler.nextWakeUpAt(armed())).isEqualTo(ARMED_AT + TIMEOUT)
    }

    @Test
    fun `une remise a zero repousse le reveil d autant`() {
        val reset = armed().copy(lastActivityAt = ARMED_AT + 3_600_000L)
        assertThat(SafetyCallAlarmScheduler.nextWakeUpAt(reset))
            .isEqualTo(ARMED_AT + 3_600_000L + TIMEOUT)
    }

    @Test
    fun `un deadman desactive n arme aucun reveil`() {
        assertThat(SafetyCallAlarmScheduler.nextWakeUpAt(armed().copy(enabled = false))).isNull()
    }

    @Test
    fun `un deadman jamais initialise n arme aucun reveil`() {
        assertThat(SafetyCallAlarmScheduler.nextWakeUpAt(armed().copy(lastActivityAt = 0L)))
            .isNull()
    }

    /**
     * Une séquence ouverte a la priorité : le rendez-vous utile est la relance, pas une échéance
     * initiale déjà consommée. Sans ce cas, l'alarme serait posée dans le passé et le réveil
     * partirait en boucle.
     */
    @Test
    fun `une sequence ouverte se reveille sur la prochaine relance`() {
        val triggered = armed().copy(triggeredAt = ARMED_AT + TIMEOUT, messagesSent = 2)
        assertThat(SafetyCallAlarmScheduler.nextWakeUpAt(triggered))
            .isEqualTo(ARMED_AT + TIMEOUT + 2 * SafetyCallConfig.RELANCE_INTERVAL_MS)
    }

    @Test
    fun `une sequence terminee n arme plus rien`() {
        val done = armed().copy(
            triggeredAt = ARMED_AT + TIMEOUT,
            messagesSent = SafetyCallConfig.TOTAL_MESSAGES,
        )
        assertThat(SafetyCallAlarmScheduler.nextWakeUpAt(done)).isNull()
    }
}
