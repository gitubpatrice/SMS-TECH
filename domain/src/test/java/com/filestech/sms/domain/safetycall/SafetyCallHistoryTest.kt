package com.filestech.sms.domain.safetycall

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.27.3 — verrouille l'**archivage des déclenchements** au moment où le cycle se referme.
 *
 * # Pourquoi ces tests existent
 *
 * `triggeredAt` et `messagesSent` étaient la seule trace qu'une alerte était partie, et
 * [SafetyCallConfig.withActivityReset] les effaçait. L'application ne pouvait donc pas répondre à
 * « est-ce que ça s'est déjà déclenché, quand, et vers qui ? » — la notification, qui se balaie et
 * ne survit pas au redémarrage, n'était pas une réponse.
 *
 * L'archivage vit dans `withActivityReset` parce que c'est le **point de passage unique** de toutes
 * les remises à zéro : « Je vais bien », tap sur la notification, ouverture de l'application,
 * réarmement depuis la notification et réactivation du switch y passent tous. Ces tests verrouillent
 * ce choix : si quelqu'un ajoute demain un cinquième chemin qui contourne cette fonction, c'est
 * l'archivage qu'il perdra, et rien ne le lui dira.
 *
 * # Aucune horloge implicite
 *
 * `withActivityReset` a deux horloges par défaut, dont `SystemClock.elapsedRealtime()`, qui n'existe
 * pas sur la JVM. Elles sont donc toujours passées explicitement — ce qui rend aussi les assertions
 * déterministes.
 */
class SafetyCallHistoryTest {

    private companion object {
        const val ARMED_AT = 1_700_000_000_000L
        const val MONO_AT = 5_000_000L
        const val TRIGGERED_AT = ARMED_AT + 60 * 60 * 1000L
        const val RESET_AT = TRIGGERED_AT + 60 * 60 * 1000L
        const val RESET_MONO = MONO_AT + 2 * 60 * 60 * 1000L
        val TIMEOUT = SafetyCallConfig.TIMEOUT_24H_MS
    }

    private fun armed(vararg contacts: SafetyCallContact) = SafetyCallConfig(
        enabled = true,
        timeoutMs = TIMEOUT,
        lastActivityAt = ARMED_AT,
        monotonicLastActivityAt = MONO_AT,
        contacts = if (contacts.isEmpty()) listOf(maman()) else contacts.toList(),
    )

    private fun maman() = SafetyCallContact(displayName = "Maman", phoneNumber = "+33607231541")

    private fun SafetyCallConfig.reset(disarm: Boolean = false) =
        withActivityReset(nowMs = RESET_AT, nowMonoMs = RESET_MONO, disarmIfTriggered = disarm)

    /**
     * Le cas nominal : une séquence complète, refermée par « Je vais bien ». Ce que l'utilisateur
     * doit pouvoir relire des semaines plus tard.
     */
    @Test
    fun `une sequence complete est archivee au reset`() {
        val fini = armed().copy(
            triggeredAt = TRIGGERED_AT,
            messagesSent = SafetyCallConfig.TOTAL_MESSAGES,
            claimedAt = 0L,
        )
        val apres = fini.reset(disarm = true)

        assertThat(apres.history).hasSize(1)
        val record = apres.history.single()
        assertThat(record.triggeredAt).isEqualTo(TRIGGERED_AT)
        assertThat(record.messagesDelivered).isEqualTo(SafetyCallConfig.TOTAL_MESSAGES)
        assertThat(record.totalMessages).isEqualTo(SafetyCallConfig.TOTAL_MESSAGES)
        assertThat(record.recipients).containsExactly("Maman")
        assertThat(record.isComplete).isTrue()
        // Non-vacuité : les champs archivés ont bien été effacés de l'état courant, sans quoi ce
        // test passerait même sans archivage.
        assertThat(apres.triggeredAt).isEqualTo(0L)
        assertThat(apres.messagesSent).isEqualTo(0)
    }

    /** Une séquence écourtée garde son compte partiel : c'est l'information juste, pas un échec. */
    @Test
    fun `une sequence interrompue est archivee avec son compte partiel`() {
        val interrompu = armed().copy(
            triggeredAt = TRIGGERED_AT,
            messagesSent = 2,
            claimedAt = 0L,
        )
        val record = interrompu.reset(disarm = true).history.single()

        assertThat(record.messagesDelivered).isEqualTo(2)
        assertThat(record.isComplete).isFalse()
    }

    /**
     * 🔴 Le cas qui rendrait l'historique menteur : `TriggerSafetyCallUseCase` remet `triggeredAt` à
     * zéro quand un envoi a **totalement échoué** (restitution de créneau). Un enregistrement
     * « déclenché, 0 message parti » ferait croire à une alerte qui n'a jamais eu lieu.
     *
     * C'est cette garde qui permet de tout traiter dans `withActivityReset` sans toucher au chemin
     * d'envoi.
     */
    @Test
    fun `un creneau reserve mais jamais conclu n est pas archive`() {
        val enVol = armed().copy(
            triggeredAt = TRIGGERED_AT,
            messagesSent = 1,
            claimedAt = TRIGGERED_AT,
        )
        assertThat(enVol.messagesDelivered).isEqualTo(0) // non-vacuité
        assertThat(enVol.reset().history).isEmpty()
    }

    /**
     * 🔴 v1.27.3 (relecture Gemini du 2026-08-06, F-02) — LE CYCLE ACHEVÉ DOIT ÊTRE LISIBLE AVANT
     * D'ÊTRE ARCHIVÉ.
     *
     * Le désarmement de fin de séquence est écrit dans la transaction du dernier envoi, **sans
     * passer par `withActivityReset`**. Et la remise à zéro d'ouverture d'application est conditionnée
     * à `enabled`, faux après ce désarmement : elle ne l'archive donc pas davantage. Un écran qui
     * n'aurait lu que `history` aurait affiché « aucun déclenchement enregistré » à l'instant précis
     * où quatre SMS venaient de partir chez les proches.
     */
    @Test
    fun `une sequence achevee mais pas encore archivee est deja lisible`() {
        val acheveNonArchive = armed().copy(
            enabled = false, // desarmement de fin de sequence
            triggeredAt = TRIGGERED_AT,
            messagesSent = SafetyCallConfig.TOTAL_MESSAGES,
            claimedAt = 0L,
        )
        assertThat(acheveNonArchive.history).isEmpty() // non-vacuité : rien n'est encore archivé

        val lisible = acheveNonArchive.historyWithCurrentCycle
        assertThat(lisible).hasSize(1)
        assertThat(lisible.single().triggeredAt).isEqualTo(TRIGGERED_AT)
        assertThat(lisible.single().isComplete).isTrue()
    }

    /**
     * ⚠️ Et il ne doit pas apparaître DEUX fois une fois archivé — c'est le risque de faire lire à
     * l'écran une liste qui contient le cycle courant.
     */
    @Test
    fun `le cycle archive n apparait pas en double`() {
        val acheve = armed().copy(
            triggeredAt = TRIGGERED_AT,
            messagesSent = SafetyCallConfig.TOTAL_MESSAGES,
            claimedAt = 0L,
        )
        val apres = acheve.reset(disarm = true)

        assertThat(apres.history).hasSize(1)
        // `triggeredAt` est retombé à zéro dans la même opération : le cycle courant n'existe plus,
        // donc la lecture rend exactement l'archive.
        assertThat(apres.historyWithCurrentCycle).isEqualTo(apres.history)
    }

    /** Rien n'est annoncé avant qu'un envoi soit conclu : un créneau en vol reste invisible. */
    @Test
    fun `un premier creneau en vol n apparait pas encore dans la lecture`() {
        val enVol = armed().copy(
            triggeredAt = TRIGGERED_AT,
            messagesSent = 1,
            claimedAt = TRIGGERED_AT,
        )
        assertThat(enVol.historyWithCurrentCycle).isEmpty()
    }

    /** Ouvrir l'application dix fois par jour ne doit rien écrire. */
    @Test
    fun `un reset sans declenchement n archive rien`() {
        assertThat(armed().reset().history).isEmpty()
    }

    /**
     * DataStore relit et réécrit tout son fichier à chaque écriture, et chaque ouverture de
     * l'application déclenche un reset. Une liste non bornée alourdirait donc indéfiniment un
     * chemin chaud.
     */
    @Test
    fun `l historique est borne aux dernieres entrees et garde les plus recentes`() {
        var cfg = armed()
        repeat(SafetyCallConfig.MAX_HISTORY + 3) { i ->
            cfg = cfg.copy(
                triggeredAt = TRIGGERED_AT + i,
                messagesSent = SafetyCallConfig.TOTAL_MESSAGES,
                claimedAt = 0L,
            ).reset()
        }

        assertThat(cfg.history).hasSize(SafetyCallConfig.MAX_HISTORY)
        // Les plus ANCIENNES sont tombées : la première conservée est la 4ᵉ écrite.
        assertThat(cfg.history.first().triggeredAt).isEqualTo(TRIGGERED_AT + 3)
        assertThat(cfg.history.last().triggeredAt)
            .isEqualTo(TRIGGERED_AT + SafetyCallConfig.MAX_HISTORY + 2)
    }

    /** Le numéro sert de libellé quand le contact n'a pas de nom — « vers qui » doit rester lisible. */
    @Test
    fun `un contact sans nom est archive sous son numero`() {
        val anonyme = SafetyCallContact(displayName = null, phoneNumber = "+33612345678")
        val fini = armed(anonyme).copy(
            triggeredAt = TRIGGERED_AT,
            messagesSent = 1,
            claimedAt = 0L,
        )
        assertThat(fini.reset().history.single().recipients).containsExactly("+33612345678")
    }

    /**
     * ⚠️ Garde-fou de l'invariant P-01 : l'archivage ne doit pas avoir touché à `claimId`, qui doit
     * rester strictement croissant sur toute la vie de l'installation, ni à l'incrément de
     * `generation` qui invalide les workers de l'ancien cycle.
     */
    @Test
    fun `l archivage ne perturbe ni claimId ni generation`() {
        val fini = armed().copy(
            triggeredAt = TRIGGERED_AT,
            messagesSent = SafetyCallConfig.TOTAL_MESSAGES,
            claimedAt = 0L,
            claimId = 42L,
            generation = 7L,
        )
        val apres = fini.reset(disarm = true)

        assertThat(apres.claimId).isEqualTo(42L)
        assertThat(apres.generation).isEqualTo(8L)
    }

    /**
     * 🔴 v1.27.3 — LE FORMULAIRE N'ÉCRASE PLUS L'ÉTAT D'EXÉCUTION.
     *
     * L'écran de configuration enregistrait son brouillon — hydraté une seule fois à l'ouverture —
     * par-dessus `safetyCall` **en entier**. Enregistrer pendant une séquence effaçait donc l'alerte
     * en cours et **rembobinait `claimId` et `generation`**, les deux invariants posés par P-01 et
     * C-04. Ce test verrouille la frontière.
     */
    @Test
    fun `les edits du formulaire ne touchent pas l etat d execution`() {
        val live = armed().copy(
            triggeredAt = TRIGGERED_AT,
            messagesSent = 2,
            claimedAt = TRIGGERED_AT,
            claimId = 99L,
            generation = 12L,
            monotonicAccumulatedMs = 777L,
            history = listOf(SafetyCallTriggerRecord(TRIGGERED_AT - 1, 4, 4, listOf("Papy"))),
        )
        // ⚠️ Le brouillon diffère de `live` sur **tous** les champs, moteur compris, et avec des
        // valeurs non nulles : un test dont le brouillon porterait des zéros passerait aussi sur une
        // implémentation qui recopie tout, puisque zéro est indistinguable d'« absent ».
        val draft = SafetyCallConfig(
            enabled = false,
            timeoutMs = SafetyCallConfig.TIMEOUT_48H_MS,
            contacts = listOf(SafetyCallContact("Papa", "+33612345678")),
            template = SafetyCallTemplate.URGENT,
            customMessage = "texte",
            lastActivityAt = 111L,
            monotonicLastActivityAt = 222L,
            monotonicAccumulatedMs = 333L,
            triggeredAt = 444L,
            messagesSent = 1,
            claimedAt = 555L,
            claimId = 1L,
            generation = 1L,
            history = listOf(SafetyCallTriggerRecord(666L, 1, 4, listOf("Intrus"))),
        )

        val merged = live.withUserEdits(draft)

        // Les cinq champs du formulaire ont bien été pris — sans quoi le test serait vacant.
        assertThat(merged.enabled).isFalse()
        assertThat(merged.timeoutMs).isEqualTo(SafetyCallConfig.TIMEOUT_48H_MS)
        assertThat(merged.contacts).isEqualTo(draft.contacts)
        assertThat(merged.template).isEqualTo(SafetyCallTemplate.URGENT)
        assertThat(merged.customMessage).isEqualTo("texte")

        // Et RIEN du moteur n'a bougé.
        assertThat(merged.triggeredAt).isEqualTo(TRIGGERED_AT)
        assertThat(merged.messagesSent).isEqualTo(2)
        assertThat(merged.claimedAt).isEqualTo(TRIGGERED_AT)
        assertThat(merged.claimId).isEqualTo(99L)
        assertThat(merged.generation).isEqualTo(12L)
        assertThat(merged.lastActivityAt).isEqualTo(ARMED_AT)
        assertThat(merged.monotonicLastActivityAt).isEqualTo(MONO_AT)
        assertThat(merged.monotonicAccumulatedMs).isEqualTo(777L)
        assertThat(merged.history).isEqualTo(live.history)
    }
}
