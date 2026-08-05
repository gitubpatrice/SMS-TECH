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

    /**
     * 🔴 **F-01 (relecture Gemini du 2026-08-05) — l'avertissement aussi a besoin d'un réveil.**
     *
     * Seule l'échéance était programmée. L'avertissement « Confirmez que vous allez bien »
     * dépendait du tick horaire : il ne s'affichait que si un tick tombait par hasard dans la
     * fenêtre. Depuis que celle-ci est proportionnelle, celle d'un délai d'**une heure** ne dure
     * que quinze minutes — le tick n'y tombe qu'une fois sur quatre, et trois fois sur quatre de
     * vrais SMS partaient aux proches **sans que la personne ait jamais été prévenue**.
     */
    @Test
    fun `un deadman arme se reveille d abord pour AVERTIR, puis a l echeance`() {
        val deadline = ARMED_AT + TIMEOUT
        val debutFenetre = deadline - armed().warningWindowMs()

        // Loin de l'échéance : le rendez-vous utile est l'ouverture de la fenêtre d'avertissement.
        val avant = SafetyCallAlarmScheduler.nextWakeUpAt(
            armed(),
            nowMs = ARMED_AT + ONE_HOUR,
            nowMonoMs = MONO_AT + ONE_HOUR,
        )
        assertThat(avant).isEqualTo(debutFenetre)
        // Non-vacuité : c'est bien AVANT l'échéance, sinon l'avertissement n'avertirait de rien.
        assertThat(avant!!).isLessThan(deadline)

        // Une fois dans la fenêtre, le rendez-vous redevient l'échéance elle-même.
        val dedans = SafetyCallAlarmScheduler.nextWakeUpAt(
            armed(),
            nowMs = debutFenetre,
            nowMonoMs = MONO_AT + (debutFenetre - ARMED_AT),
        )
        assertThat(dedans).isEqualTo(deadline)
    }

    /**
     * Le cas qui a rendu F-01 visible : un délai d'**une heure**, le minimum que l'interface
     * propose. La fenêtre n'y vaut que quinze minutes, et aucun tick horaire ne peut la garantir.
     */
    @Test
    fun `sur un delai d une heure, l avertissement est programme a quinze minutes de l echeance`() {
        val court = armed().copy(timeoutMs = SafetyCallConfig.TIMEOUT_MIN_MS)
        val deadline = ARMED_AT + SafetyCallConfig.TIMEOUT_MIN_MS

        val at = SafetyCallAlarmScheduler.nextWakeUpAt(court, nowMs = ARMED_AT, nowMonoMs = MONO_AT)

        assertThat(at).isEqualTo(deadline - SafetyCallConfig.WARNING_WINDOW_MIN_MS)
        assertThat(court.warningWindowMs()).isEqualTo(SafetyCallConfig.WARNING_WINDOW_MIN_MS)
    }

    @Test
    fun `une remise a zero repousse le reveil d autant`() {
        val reset = armed().copy(lastActivityAt = ARMED_AT + ONE_HOUR, monotonicLastActivityAt = MONO_AT + ONE_HOUR)
        val at = SafetyCallAlarmScheduler.nextWakeUpAt(
            reset,
            nowMs = ARMED_AT + ONE_HOUR,
            nowMonoMs = MONO_AT + ONE_HOUR,
        )
        assertThat(at).isEqualTo(ARMED_AT + ONE_HOUR + TIMEOUT - reset.warningWindowMs())
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
        // restantes de l'horloge murale. Le rendez-vous est celui de l'avertissement (F-01), qui
        // se déduit de la MÊME échéance — donc le décalage monotone se répercute à l'identique.
        val echeanceMonotone = now + (TIMEOUT - 2 * ONE_HOUR)
        assertThat(at).isEqualTo(echeanceMonotone - armed().warningWindowMs())
        // LE POINT : la monotone repousse, elle n'avance jamais. Comparé à ce que l'horloge murale
        // seule aurait donné, le réveil est bien plus TARDIF.
        assertThat(at!!).isGreaterThan(ARMED_AT + TIMEOUT - armed().warningWindowMs())
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
                // Puis on entre dans la fenetre d'avertissement : la decision devient l'echeance.
                TimedConfig(armed(), deadline - 60_000L, MONO_AT + TIMEOUT - 60_000L),
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

        // v1.27.2 (audit Codex, C-05) — la deuxième décision n'est PLUS la relance à quinze
        // minutes, mais **l'expiration du bail à deux minutes**.
        //
        // Tant qu'un créneau est réservé, le prochain instant utile est celui où l'on saura si son
        // propriétaire est mort. Avant, le bail rendait un créneau abandonné éligible à la reprise
        // sans garantir aucun réveil pour la faire : les deux minutes annoncées pouvaient en valoir
        // quinze, ou soixante sur le dernier créneau.
        assertThat(decisions).containsExactly(
            deadline - armed().warningWindowMs(),
            deadline,
            deadline + SafetyCallConfig.CLAIM_LEASE_MS,
            deadline,
        ).inOrder()
        assertThat(decisions.last()).isLessThan(afterDeadlineWall)
        // Ce qui compte pour la ponctualité : le réveil du bail tombe bien AVANT celui de la
        // relance qu'il précède.
        assertThat(deadline + SafetyCallConfig.CLAIM_LEASE_MS)
            .isLessThan(deadline + SafetyCallConfig.RELANCE_INTERVAL_MS)
    }

    /**
     * 🔴 **P-03 (audit Codex du 2026-08-05) — l'arrondi allait VERS LE PASSÉ.**
     *
     * La quantification s'écrivait `at / 1000 * 1000`. Une échéance à `T = …999 ms` était donc
     * programmée à `T − 999 ms`. Le système garantit de ne pas livrer avant l'instant demandé, mais
     * l'instant demandé était **déjà antérieur à l'échéance métier** : livrée dans cet intervalle,
     * l'alarme réveillait un worker qui ne trouvait rien à faire et rendait `success()`.
     *
     * L'alarme était alors **consommée sans rien produire**, et rien ne la reposait : le collecteur
     * recalcule la même valeur quantifiée et `distinctUntilChanged` supprime l'émission. La
     * ponctualité retombait sur le tick horaire — jusqu'à une heure plus tard.
     *
     * Une seconde de trop ne coûte rien ; une milliseconde de moins coûtait une heure.
     */
    @Test
    fun `une echeance non alignee est arrondie vers le futur, jamais vers le passe`() {
        val offset = 999L
        val cfg = armed().copy(
            lastActivityAt = ARMED_AT + offset,
            monotonicLastActivityAt = MONO_AT + offset,
        )
        val deadline = ARMED_AT + offset + TIMEOUT

        // On se place DANS la fenetre d'avertissement pour que l'echeance domine la decision.
        val ecoule = TIMEOUT - 60_000L
        val at = SafetyCallAlarmScheduler.nextWakeUpAt(
            cfg,
            nowMs = ARMED_AT + offset + ecoule,
            nowMonoMs = MONO_AT + offset + ecoule,
        )

        // LE POINT : le réveil ne peut PAS tomber avant l'échéance réelle.
        assertThat(at).isNotNull()
        assertThat(at!!).isAtLeast(deadline)
        // Arrondi à la seconde SUPÉRIEURE : …999 → la seconde suivante, soit 1 ms plus tard.
        assertThat(at).isEqualTo(deadline + 1L)
        // Non-vacuité : l'ancien arrondi vers le bas serait tombé 999 ms AVANT l'échéance.
        assertThat(deadline / 1_000L * 1_000L).isLessThan(deadline)
    }

    /** Une échéance déjà alignée à la seconde n'est pas déplacée par l'arrondi. */
    @Test
    fun `une echeance alignee a la seconde n est pas deplacee`() {
        val ecoule = TIMEOUT - 60_000L
        val at = SafetyCallAlarmScheduler.nextWakeUpAt(
            armed(),
            nowMs = ARMED_AT + ecoule,
            nowMonoMs = MONO_AT + ecoule,
        )
        assertThat(at).isEqualTo(ARMED_AT + TIMEOUT)
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
