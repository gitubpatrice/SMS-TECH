package com.filestech.sms.system.scheduler

import com.filestech.sms.domain.safetycall.SafetyCallConfig
import com.filestech.sms.domain.safetycall.SafetyCallContact
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * v1.27.2 — verrouille **quand** le Safety call se réveille.
 *
 * La décision entière tient dans [SafetyCallAlarmScheduler.nextWakeUpAt], volontairement pure pour
 * être testable sans appareil. Une erreur ici ne se voit pas : elle produit un deadman qui part
 * trop tôt, un deadman qui ne part pas — ou une **boucle de réveil** qui vide la batterie.
 */
class SafetyCallAlarmSchedulerTest {

    private data class TimedConfig(
        val config: SafetyCallConfig,
        val wallNow: Long,
        val monotonicNow: Long,
    )

    private companion object {
        const val ARMED_AT = 1_700_000_000_000L
        const val MONO_AT = 5_000_000L
        const val TIMEOUT = SafetyCallConfig.TIMEOUT_24H_MS
        const val ONE_HOUR = 60 * 60 * 1000L
    }

    private fun armed() = SafetyCallConfig(
        enabled = true,
        timeoutMs = TIMEOUT,
        lastActivityAt = ARMED_AT,
        monotonicLastActivityAt = MONO_AT,
        monotonicAccumulatedMs = 0L,
        contacts = listOf(SafetyCallContact(phoneNumber = "+33611111111")),
    )

    @Test
    fun `un deadman arme se reveille a l echeance, pas avant`() {
        // Les deux horloges avancent du même pas : l'échéance monotone tombe sur la murale.
        val at = SafetyCallAlarmScheduler.nextWakeUpAt(
            armed(),
            nowMs = ARMED_AT + ONE_HOUR,
            nowMonoMs = MONO_AT + ONE_HOUR,
        )
        assertThat(at).isEqualTo(ARMED_AT + TIMEOUT)
    }

    @Test
    fun `une remise a zero repousse le reveil d autant`() {
        val reset = armed().copy(lastActivityAt = ARMED_AT + ONE_HOUR, monotonicLastActivityAt = MONO_AT + ONE_HOUR)
        val at = SafetyCallAlarmScheduler.nextWakeUpAt(
            reset,
            nowMs = ARMED_AT + ONE_HOUR,
            nowMonoMs = MONO_AT + ONE_HOUR,
        )
        assertThat(at).isEqualTo(ARMED_AT + ONE_HOUR + TIMEOUT)
    }

    /**
     * 🔴 **La garde anti-boucle.**
     *
     * Le deadman n'expire que quand les DEUX horloges ont expiré. Après un redémarrage, la
     * monotone est en retard sur la murale. Ne regarder que la murale poserait l'alarme à un
     * instant où le worker ne trouve rien à faire — il repartirait, la configuration changerait au
     * jalon, l'alarme serait reposée au même instant passé, et ainsi de suite : **un réveil en
     * boucle**. Le réveil doit donc être le PLUS TARDIF des deux.
     */
    @Test
    fun `une horloge monotone en retard repousse le reveil, jamais l inverse`() {
        val now = ARMED_AT + 10 * ONE_HOUR
        // Redémarrage : seules 2 h ont été capitalisées côté monotone, contre 10 h côté murale.
        val nowMono = MONO_AT + 2 * ONE_HOUR

        val at = SafetyCallAlarmScheduler.nextWakeUpAt(armed(), nowMs = now, nowMonoMs = nowMono)

        // Il reste 22 h de compteur monotone à courir : c'est ça qui fait foi, pas les 14 h
        // restantes de l'horloge murale.
        assertThat(at).isEqualTo(now + (TIMEOUT - 2 * ONE_HOUR))
        assertThat(at).isGreaterThan(ARMED_AT + TIMEOUT)
    }

    /**
     * **L'invariant qui rend la déduplication possible.**
     *
     * Le worker jalonne le compteur monotone à chaque tick horaire : il déplace du temps de
     * l'ancre vers le capital, **sans changer la somme**. L'instant de réveil calculé doit donc
     * être rigoureusement identique avant et après un jalon — sinon l'observateur de
     * `MainApplication` reposerait une alarme toutes les heures pour rien.
     */
    @Test
    fun `un jalon du compteur monotone ne deplace pas le reveil`() {
        val now = ARMED_AT + 3 * ONE_HOUR
        val nowMono = MONO_AT + 3 * ONE_HOUR
        val avant = armed()
        // Ce que le worker écrit au tick : capital += segment courant, ancre = nowMono.
        val apres = avant.copy(
            monotonicAccumulatedMs = avant.monoElapsedMs(nowMono),
            monotonicLastActivityAt = nowMono,
        )

        assertThat(SafetyCallAlarmScheduler.nextWakeUpAt(apres, now, nowMono))
            .isEqualTo(SafetyCallAlarmScheduler.nextWakeUpAt(avant, now, nowMono))
    }

    /**
     * Reproduit le flux de décision de `MainApplication` avec ses deux horloges injectées. Après
     * un échec total, la restitution repasse par l'échéance initiale désormais passée. Le jalon
     * suivant ne doit pas réémettre cette même décision et réarmer une boucle immédiate.
     */
    @Test
    fun `le flux deduplique une echeance passee apres restitution du creneau`() {
        val deadline = ARMED_AT + TIMEOUT
        val claimed = armed().copy(triggeredAt = deadline, messagesSent = 1, claimedAt = deadline)
        val rolledBack = armed()
        val afterDeadlineWall = deadline + 1_000L
        val afterDeadlineMono = MONO_AT + TIMEOUT + 1_000L
        val checkpointed = rolledBack.copy(
            monotonicAccumulatedMs = rolledBack.monoElapsedMs(afterDeadlineMono),
            monotonicLastActivityAt = afterDeadlineMono,
        )

        val decisions = runBlocking {
            listOf(
                TimedConfig(armed(), ARMED_AT + ONE_HOUR, MONO_AT + ONE_HOUR),
                TimedConfig(claimed, deadline, MONO_AT + TIMEOUT),
                TimedConfig(rolledBack, afterDeadlineWall, afterDeadlineMono),
                TimedConfig(checkpointed, afterDeadlineWall, afterDeadlineMono),
            ).asFlow()
                .map { timed ->
                    SafetyCallAlarmScheduler.nextWakeUpAt(
                        timed.config,
                        nowMs = timed.wallNow,
                        nowMonoMs = timed.monotonicNow,
                    )
                }
                .distinctUntilChanged()
                .toList()
        }

        assertThat(decisions).containsExactly(
            deadline,
            deadline + SafetyCallConfig.RELANCE_INTERVAL_MS,
            deadline,
        ).inOrder()
        assertThat(decisions.last()).isLessThan(afterDeadlineWall)
    }

    @Test
    fun `un deadman desactive n arme aucun reveil`() {
        assertThat(
            SafetyCallAlarmScheduler.nextWakeUpAt(
                armed().copy(enabled = false),
                ARMED_AT,
                MONO_AT,
            ),
        ).isNull()
    }

    @Test
    fun `un deadman jamais initialise n arme aucun reveil`() {
        assertThat(
            SafetyCallAlarmScheduler.nextWakeUpAt(
                armed().copy(lastActivityAt = 0L),
                ARMED_AT,
                MONO_AT,
            ),
        ).isNull()
    }

    /**
     * Ancre monotone absente = configuration héritée v1.9.0 : `isExpired()` rend `false`, donc un
     * réveil ne trouverait rien à faire et bouclerait. `MainApplication` répare cet état au
     * démarrage ; ici, on refuse simplement de poser l'alarme.
     */
    @Test
    fun `une ancre monotone absente n arme aucun reveil`() {
        assertThat(
            SafetyCallAlarmScheduler.nextWakeUpAt(
                armed().copy(monotonicLastActivityAt = 0L),
                ARMED_AT,
                MONO_AT,
            ),
        ).isNull()
    }

    /**
     * Une séquence ouverte a la priorité : le rendez-vous utile est la relance, pas une échéance
     * initiale déjà consommée.
     */
    @Test
    fun `une sequence ouverte se reveille sur la prochaine relance`() {
        val triggered = armed().copy(triggeredAt = ARMED_AT + TIMEOUT, messagesSent = 2)
        assertThat(SafetyCallAlarmScheduler.nextWakeUpAt(triggered, ARMED_AT, MONO_AT))
            .isEqualTo(ARMED_AT + TIMEOUT + 2 * SafetyCallConfig.RELANCE_INTERVAL_MS)
    }

    @Test
    fun `une sequence terminee n arme plus rien`() {
        val done = armed().copy(
            triggeredAt = ARMED_AT + TIMEOUT,
            messagesSent = SafetyCallConfig.TOTAL_MESSAGES,
        )
        assertThat(SafetyCallAlarmScheduler.nextWakeUpAt(done, ARMED_AT, MONO_AT)).isNull()
    }
}
