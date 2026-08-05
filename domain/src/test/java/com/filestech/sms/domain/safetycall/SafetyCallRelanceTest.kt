package com.filestech.sms.domain.safetycall

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.27.2 — verrouille l'**escalade bornée** du Safety call : le message initial, puis
 * [SafetyCallConfig.RELANCE_COUNT] relances espacées de [SafetyCallConfig.RELANCE_INTERVAL_MS],
 * puis plus rien.
 *
 * # Ce qui est réellement en jeu
 *
 * Cette logique décide quand partent de **vrais SMS** vers les proches de quelqu'un. Les deux
 * échecs possibles ne se valent pas : ne pas partir laisse croire à une protection qui n'existe
 * pas, partir sans fin apprend aux contacts à ignorer l'alerte — ce qui la détruit le jour où elle
 * est vraie. Les tests ci-dessous tiennent les **deux bords** : la séquence va bien jusqu'au bout,
 * et elle s'arrête bien.
 *
 * # Aucune horloge implicite
 *
 * Toutes les horloges sont passées explicitement. Les valeurs par défaut de [SafetyCallConfig]
 * appellent `System.currentTimeMillis()` et `SystemClock.elapsedRealtime()` : les laisser jouer
 * rendrait ces tests dépendants de l'heure de la machine, donc instables un jour sur mille — le
 * genre de test qui apprend à relancer plutôt qu'à lire.
 */
class SafetyCallRelanceTest {

    private companion object {
        /** Horodatage arbitraire mais FIXE du premier envoi. */
        const val TRIGGERED_AT = 1_700_000_000_000L
        const val TIMEOUT = SafetyCallConfig.TIMEOUT_24H_MS
        val INTERVAL = SafetyCallConfig.RELANCE_INTERVAL_MS

        /** Ancre monotone non nulle : `0L` signifierait « config héritée v1.9.0 », jamais expirée. */
        const val MONO_ANCHOR = 1L
    }

    /**
     * Configuration armée dont le décompte initial est **déjà écoulé** sur les deux horloges, avec
     * [messagesSent] messages déjà partis. Le capital monotone est posé en dur plutôt que calculé
     * depuis une horloge courante, pour la même raison que ci-dessus.
     */
    private fun config(messagesSent: Int, triggeredAt: Long = TRIGGERED_AT) = SafetyCallConfig(
        enabled = true,
        timeoutMs = TIMEOUT,
        lastActivityAt = TRIGGERED_AT - TIMEOUT,
        monotonicLastActivityAt = MONO_ANCHOR,
        monotonicAccumulatedMs = TIMEOUT,
        contacts = listOf(SafetyCallContact(phoneNumber = "+33611111111")),
        triggeredAt = triggeredAt,
        messagesSent = messagesSent,
    )

    @Test
    fun `un deadman jamais declenche n'a aucune relance en attente`() {
        val armed = config(messagesSent = 0, triggeredAt = 0L)

        assertThat(armed.isTriggered).isFalse()
        assertThat(armed.hasRelancePending).isFalse()
        assertThat(armed.nextRelanceAt()).isNull()
        assertThat(armed.isRelanceDue(nowMs = TRIGGERED_AT + INTERVAL * 10)).isFalse()
    }

    @Test
    fun `chaque relance tombe un intervalle apres la precedente`() {
        for (sent in 1 until SafetyCallConfig.TOTAL_MESSAGES) {
            val cfg = config(messagesSent = sent)
            assertThat(cfg.hasRelancePending).isTrue()
            assertThat(cfg.nextRelanceAt()).isEqualTo(TRIGGERED_AT + sent * INTERVAL)
        }
    }

    /**
     * **La borne.** Sans elle, le deadman relancerait indéfiniment des proches que personne ne peut
     * arrêter — celui qui le pourrait est précisément celui qui ne regarde pas son téléphone.
     */
    @Test
    fun `la sequence s'arrete apres le dernier message`() {
        val done = config(messagesSent = SafetyCallConfig.TOTAL_MESSAGES)

        assertThat(done.isTriggered).isTrue()
        assertThat(done.hasRelancePending).isFalse()
        assertThat(done.nextRelanceAt()).isNull()
        // Même très longtemps après, plus rien n'est dû.
        assertThat(done.isRelanceDue(nowMs = TRIGGERED_AT + INTERVAL * 100)).isFalse()
    }

    @Test
    fun `une relance n'est due qu'a l'heure dite`() {
        val cfg = config(messagesSent = 1)
        val due = TRIGGERED_AT + INTERVAL

        assertThat(cfg.isRelanceDue(nowMs = due - 1L)).isFalse()
        assertThat(cfg.isRelanceDue(nowMs = due)).isTrue()
        assertThat(cfg.isRelanceDue(nowMs = due + 1L)).isTrue()
    }

    /**
     * **Régression du message initial qui repartait à chaque tick.**
     *
     * Le déclenchement ne remet pas `lastActivityAt` à zéro — le décompte initial reste donc
     * expiré. Sans la sortie anticipée sur `isTriggered`, chaque tick horaire aurait renvoyé le
     * message d'origine, en plus des relances, et sans jamais s'arrêter.
     *
     * La seconde assertion prouve la **non-vacuité** : le jumeau non déclenché, lui, est bien
     * expiré. Sans elle, une implémentation qui rendrait toujours `false` passerait ce test.
     */
    @Test
    fun `un declenchement clot le decompte initial`() {
        val late = TRIGGERED_AT + INTERVAL * 100
        val lateMono = MONO_ANCHOR + TIMEOUT * 10

        assertThat(config(messagesSent = 1).isExpired(nowMs = late, nowMonoMs = lateMono)).isFalse()
        assertThat(
            config(messagesSent = 0, triggeredAt = 0L).isExpired(nowMs = late, nowMonoMs = lateMono),
        ).isTrue()
    }

    /**
     * L'avertissement « confirme que tu vas bien » annonce un déclenchement à venir. Une fois le
     * message parti, il n'annonce plus rien — et le laisser s'afficher ferait croire qu'il est
     * encore temps d'empêcher l'alerte, alors qu'elle est déjà chez les contacts.
     *
     * Seconde assertion = non-vacuité : le jumeau non déclenché, à la même seconde, est bien dans
     * la fenêtre.
     */
    @Test
    fun `plus d'avertissement une fois le message parti`() {
        // Une heure avant l'expiration : dans la fenêtre de 6 h, sur les deux horloges.
        val inWindowWall = (TRIGGERED_AT - TIMEOUT) + TIMEOUT - 60 * 60 * 1000L
        val inWindowMono = MONO_ANCHOR
        val warned = config(messagesSent = 0, triggeredAt = 0L)
            .copy(monotonicAccumulatedMs = TIMEOUT - 60 * 60 * 1000L)

        assertThat(warned.isInWarningWindow(nowMs = inWindowWall, nowMonoMs = inWindowMono)).isTrue()
        assertThat(
            warned.copy(triggeredAt = TRIGGERED_AT, messagesSent = 1)
                .isInWarningWindow(nowMs = inWindowWall, nowMonoMs = inWindowMono),
        ).isFalse()
    }

    /**
     * Les textes doivent **différer** : répéter le message initial mot pour mot ressemblerait à un
     * défaut de l'application, pas à une insistance. Et le dernier doit s'annoncer comme tel, sans
     * quoi un contact attendrait une suite qui ne viendra jamais au lieu d'agir.
     */
    @Test
    fun `les textes de relance progressent et le dernier s'annonce`() {
        val textes = (1..SafetyCallConfig.RELANCE_COUNT).map { SafetyCallTemplate.renderRelance(it) }

        assertThat(textes.toSet()).hasSize(SafetyCallConfig.RELANCE_COUNT)
        textes.forEach { assertThat(it).isNotEmpty() }
        // Le délai annoncé dans le texte doit correspondre au délai réel, sinon le message ment.
        assertThat(textes[0]).contains("15 minutes")
        assertThat(textes[1]).contains("30 minutes")
        assertThat(textes[2]).contains("45 minutes")
        assertThat(textes.last()).contains("Dernière alerte")
        // Aucune relance ne doit se faire passer pour le message initial.
        val initial = SafetyCallTemplate.CHECK_IN.render(TIMEOUT)
        assertThat(textes).doesNotContain(initial)
    }

    /**
     * v1.27.2 (relecture Gemini du 2026-08-05) — chaque relance doit **nommer l'application** et
     * **se suffire à elle-même**.
     *
     * Nommer : un SMS reçu en pleine nuit disant « vérifie que je vais bien », sans émetteur
     * identifiable, ressemble à du hameçonnage et se fait ignorer.
     *
     * Se suffire : le message initial peut ne jamais être arrivé — réseau coupé, ou processus tué
     * entre la réservation du créneau et l'envoi. Une relance qui renvoie au message précédent
     * serait alors incompréhensible pour le seul contact qui reçoit quelque chose.
     */
    @Test
    fun `chaque relance nomme l application et se suffit a elle-meme`() {
        for (index in 1..SafetyCallConfig.RELANCE_COUNT) {
            val texte = SafetyCallTemplate.renderRelance(index)
            assertThat(texte).contains("SMS Tech")
            // « sans réponse de ma part » est faux — le contact n'a posé aucune question. Ce qui
            // manque est de l'ACTIVITÉ sur le téléphone.
            assertThat(texte).doesNotContain("sans réponse")
            assertThat(texte).contains("activité")
            // Aucun renvoi à un message que le contact n'a peut-être jamais reçu.
            assertThat(texte).doesNotContain("relance")
        }
    }

    /**
     * v1.27.2 (relecture Gemini du 2026-08-05) — **la fenêtre d'avertissement était fausse sur les
     * délais courts.**
     *
     * Elle valait 6 h en dur, quelle que soit la durée. Avec le délai d'une heure — le minimum que
     * l'interface propose — la condition `écoulé ≥ délai − 6 h` était vraie **dès l'armement** :
     * la notification « Confirme que tu vas bien » s'affichait immédiatement et ne quittait plus la
     * barre d'état. Un avertissement permanent n'avertit plus de rien.
     */
    @Test
    fun `la fenetre d avertissement est proportionnee a la duree`() {
        val uneHeure = SafetyCallConfig(timeoutMs = 60 * 60 * 1000L)
        val vingtQuatre = SafetyCallConfig(timeoutMs = SafetyCallConfig.TIMEOUT_24H_MS)
        val trenteJours = SafetyCallConfig(timeoutMs = SafetyCallConfig.TIMEOUT_MAX_MS)

        assertThat(uneHeure.warningWindowMs()).isEqualTo(15 * 60 * 1000L)
        assertThat(vingtQuatre.warningWindowMs()).isEqualTo(6 * 60 * 60 * 1000L)
        assertThat(trenteJours.warningWindowMs()).isEqualTo(SafetyCallConfig.WARNING_WINDOW_MAX_MS)
    }

    /**
     * Le point concret : sur un délai d'une heure, **la notification ne doit PAS être là au
     * premier instant**. C'est ce que Patrice a constaté sur son téléphone le 2026-08-05.
     */
    @Test
    fun `sur un delai d une heure, pas d avertissement des l armement`() {
        val uneHeure = 60 * 60 * 1000L
        val cfg = SafetyCallConfig(
            enabled = true,
            timeoutMs = uneHeure,
            lastActivityAt = TRIGGERED_AT,
            monotonicLastActivityAt = MONO_ANCHOR,
            contacts = listOf(SafetyCallContact(phoneNumber = "+33611111111")),
        )

        // À l'armement : rien.
        assertThat(cfg.isInWarningWindow(nowMs = TRIGGERED_AT, nowMonoMs = MONO_ANCHOR)).isFalse()
        // À H+30 : toujours rien.
        assertThat(
            cfg.isInWarningWindow(
                nowMs = TRIGGERED_AT + 30 * 60 * 1000L,
                nowMonoMs = MONO_ANCHOR + 30 * 60 * 1000L,
            ),
        ).isFalse()
        // À H+50, soit 10 min avant l'échéance : l'avertissement est là. Non-vacuité.
        assertThat(
            cfg.isInWarningWindow(
                nowMs = TRIGGERED_AT + 50 * 60 * 1000L,
                nowMonoMs = MONO_ANCHOR + 50 * 60 * 1000L,
            ),
        ).isTrue()
    }
}
