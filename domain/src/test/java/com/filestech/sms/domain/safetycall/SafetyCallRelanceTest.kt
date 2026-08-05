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
        assertThat(textes.last()).contains("Dernier")
        // Aucune relance ne doit se faire passer pour le message initial.
        val initial = SafetyCallTemplate.CHECK_IN.render(TIMEOUT)
        assertThat(textes).doesNotContain(initial)
    }
}
