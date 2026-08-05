package com.filestech.sms.domain.safetycall

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.27.2 (audit Codex du 2026-08-05, P-05 / P-06) — verrouille **ce que le Safety call affiche**.
 *
 * # Pourquoi ces tests existent
 *
 * La décision vivait dans `MainApplication.onCreate`, donc hors de portée de tout test — et c'est
 * exactement pour cela que trois défauts y ont vécu sans être vus :
 *
 *  1. l'avertissement de pré-déclenchement n'était **jamais** retiré au déclenchement : les deux
 *     notifications restaient affichées côte à côte ;
 *  2. 🔴 il **survivait à l'entrée en mode leurre**, qui ne retirait que la séquence — révélant à
 *     qui tenait le téléphone qu'une fonction d'alerte existait et courait ;
 *  3. la séquence annonçait « alerte envoyée, 1 message sur 4 » **avant qu'un seul SMS ne parte**,
 *     parce qu'elle lisait le compteur des créneaux *réservés*.
 *
 * Les trois se testent ici sur des états réels, sans appareil ni gestionnaire de notifications.
 *
 * # Aucune horloge implicite
 *
 * Les deux horloges sont passées explicitement : le deadman n'expire que quand la murale **et** la
 * monotone ont expiré, et un test qui laisserait l'une par défaut serait faux la moitié du temps.
 */
class SafetyCallNoticeTest {

    private companion object {
        const val ARMED_AT = 1_700_000_000_000L
        const val MONO_AT = 5_000_000L
        val TIMEOUT = SafetyCallConfig.TIMEOUT_24H_MS
        const val ONE_HOUR = 60 * 60 * 1000L
    }

    private fun armed() = SafetyCallConfig(
        enabled = true,
        timeoutMs = TIMEOUT,
        lastActivityAt = ARMED_AT,
        monotonicLastActivityAt = MONO_AT,
        contacts = listOf(SafetyCallContact(phoneNumber = "+33611111111")),
    )

    /** Instant situé dans la fenêtre d'avertissement : 20 h écoulées sur un délai de 24 h. */
    private fun decideAt(
        cfg: SafetyCallConfig,
        elapsed: Long,
        isDecoy: Boolean = false,
    ): SafetyCallNotice = SafetyCallNotice.decide(
        cfg = cfg,
        isDecoy = isDecoy,
        nowMs = ARMED_AT + elapsed,
        nowMonoMs = MONO_AT + elapsed,
    )

    @Test
    fun `un deadman arme hors fenetre n affiche rien`() {
        assertThat(decideAt(armed(), elapsed = ONE_HOUR)).isEqualTo(SafetyCallNotice.None)
    }

    /**
     * 🔴 **F-01 (relecture Gemini du 2026-08-05)** — la notification disparaissait a l'instant
     * precis ou la quatrieme alerte partait.
     *
     * Le desarmement de fin de sequence est ecrit dans la MEME transaction que la conclusion du
     * dernier envoi. Le predicat `!enabled` ne distinguait pas les deux facons d'etre desarme :
     * quelqu'un qui reprenait son telephone une heure plus tard ne voyait aucune trace, et ignorait
     * donc que ses quatre contacts avaient recu un appel a l'aide.
     */
    @Test
    fun `une sequence terminee reste affichee malgre le desarmement`() {
        val fini = armed().copy(
            enabled = false,
            triggeredAt = ARMED_AT + TIMEOUT,
            messagesSent = SafetyCallConfig.TOTAL_MESSAGES,
            claimedAt = 0L,
        )
        assertThat(decideAt(fini, elapsed = TIMEOUT + ONE_HOUR)).isEqualTo(
            SafetyCallNotice.Sequence(
                delivered = SafetyCallConfig.TOTAL_MESSAGES,
                total = SafetyCallConfig.TOTAL_MESSAGES,
                inFlight = false,
            ),
        )
    }

    /**
     * ⚠️ Mais elle ne survit pas au **mode leurre** : sous contrainte, rien ne doit trahir qu'une
     * alerte est partie. La garde de securite passe avant tout le reste.
     */
    @Test
    fun `une sequence terminee reste invisible en mode leurre`() {
        val fini = armed().copy(
            enabled = false,
            triggeredAt = ARMED_AT + TIMEOUT,
            messagesSent = SafetyCallConfig.TOTAL_MESSAGES,
        )
        assertThat(decideAt(fini, elapsed = TIMEOUT + ONE_HOUR, isDecoy = true))
            .isEqualTo(SafetyCallNotice.None)
    }

    /**
     * Et un tap sur la notification la retire : `withActivityReset` remet `messagesSent` a zero,
     * la condition devient fausse d'elle-meme. Aucune notification collante.
     */
    @Test
    fun `un tap sur la notification terminee la fait disparaitre`() {
        val fini = armed().copy(
            enabled = false,
            triggeredAt = ARMED_AT + TIMEOUT,
            messagesSent = SafetyCallConfig.TOTAL_MESSAGES,
        )
        val apresTap = fini.withActivityReset(
            nowMs = ARMED_AT + TIMEOUT + ONE_HOUR,
            nowMonoMs = MONO_AT + TIMEOUT + ONE_HOUR,
        )
        assertThat(decideAt(apresTap, elapsed = TIMEOUT + ONE_HOUR)).isEqualTo(SafetyCallNotice.None)
    }

    @Test
    fun `un deadman desactive n affiche rien`() {
        assertThat(decideAt(armed().copy(enabled = false), elapsed = 20 * ONE_HOUR))
            .isEqualTo(SafetyCallNotice.None)
    }

    @Test
    fun `dans la fenetre de pre-declenchement, l avertissement annonce les heures restantes`() {
        val notice = decideAt(armed(), elapsed = 20 * ONE_HOUR)
        assertThat(notice).isEqualTo(SafetyCallNotice.Warning(hoursLeft = 4))
    }

    /**
     * 🔴 **C-09 (audit Codex du 2026-08-05) — la fenêtre d'avertissement pouvait être VIDE.**
     *
     * Le prédicat exigeait de CHAQUE horloge qu'elle soit entrée dans sa fenêtre **et encore sous
     * son échéance**. Or les deux divergent — redémarrage prolongé, correction d'horloge. Sur un
     * délai de 24 h (fenêtre 6 h), si l'échéance monotone tombe 8 h après la murale : avant
     * l'entrée du monotone dans sa fenêtre, il n'y est pas ; après, la murale a déjà dépassé son
     * échéance. **Intersection vide, avertissement impossible**, et de vrais SMS partaient sans
     * que personne n'ait été prévenu.
     */
    @Test
    fun `une derive d horloges superieure a la fenetre laisse quand meme avertir`() {
        val retardMono = 8 * ONE_HOUR
        val ecouleMural = 26 * ONE_HOUR
        val notice = SafetyCallNotice.decide(
            cfg = armed(),
            isDecoy = false,
            nowMs = ARMED_AT + ecouleMural,
            nowMonoMs = MONO_AT + ecouleMural - retardMono,
        )

        // Le compteur monotone vient d'entrer dans sa fenêtre (18 h sur 24 h)...
        assertThat(notice).isInstanceOf(SafetyCallNotice.Warning::class.java)
        // ...alors que le compteur mural a DÉJÀ dépassé son échéance de 2 h. C'est exactement
        // l'état que l'ancien prédicat rendait inatteignable.
        assertThat(ecouleMural).isGreaterThan(TIMEOUT)
        // Et le deadman n'a pas encore expiré : les deux horloges sont requises pour ça.
        assertThat(
            armed().isExpired(
                nowMs = ARMED_AT + ecouleMural,
                nowMonoMs = MONO_AT + ecouleMural - retardMono,
            ),
        ).isFalse()
    }

    /** Une fois les DEUX horloges expirées, l'avertissement s'efface : l'envoi prend le relais. */
    @Test
    fun `l avertissement cesse quand les deux horloges ont expire`() {
        val ecoule = 30 * ONE_HOUR
        assertThat(decideAt(armed(), elapsed = ecoule)).isEqualTo(SafetyCallNotice.None)
        assertThat(armed().isExpired(ARMED_AT + ecoule, MONO_AT + ecoule)).isTrue()
    }

    /**
     * 🔴 **P-05, le cœur du constat** : dès que la séquence démarre, l'avertissement n'a plus
     * d'objet — il annonçait un déclenchement qui a eu lieu. Les deux états sont exclusifs, donc
     * l'afficheur retire l'un en publiant l'autre.
     */
    @Test
    fun `des que la sequence demarre, ce n est plus l avertissement`() {
        val triggered = armed().copy(
            triggeredAt = ARMED_AT + TIMEOUT,
            messagesSent = 1,
        )
        val notice = decideAt(triggered, elapsed = TIMEOUT)
        assertThat(notice).isInstanceOf(SafetyCallNotice.Sequence::class.java)
        // Non-vacuité : hors séquence, cette même configuration afficherait bien l'avertissement.
        assertThat(decideAt(triggered.copy(triggeredAt = 0L, messagesSent = 0), 20 * ONE_HOUR))
            .isInstanceOf(SafetyCallNotice.Warning::class.java)
    }

    /**
     * 🔴 **P-05 — la fuite en mode leurre.**
     *
     * L'ancien réconciliateur n'appelait que `dismissSequence()` à l'entrée en session leurre.
     * L'avertissement, posé par le worker sous un autre identifiant, restait affiché : sous
     * contrainte, l'agresseur voyait qu'une fonction d'alerte existait et courait. Le mode leurre
     * doit rendre les DEUX invisibles, quel que soit l'état.
     */
    @Test
    fun `le mode leurre n affiche RIEN, ni avertissement ni sequence`() {
        val enFenetre = armed()
        val enSequence = armed().copy(triggeredAt = ARMED_AT + TIMEOUT, messagesSent = 2)

        assertThat(decideAt(enFenetre, elapsed = 20 * ONE_HOUR, isDecoy = true))
            .isEqualTo(SafetyCallNotice.None)
        assertThat(decideAt(enSequence, elapsed = TIMEOUT, isDecoy = true))
            .isEqualTo(SafetyCallNotice.None)

        // Non-vacuité : hors mode leurre, ces deux mêmes états affichent bien quelque chose.
        assertThat(decideAt(enFenetre, elapsed = 20 * ONE_HOUR)).isNotEqualTo(SafetyCallNotice.None)
        assertThat(decideAt(enSequence, elapsed = TIMEOUT)).isNotEqualTo(SafetyCallNotice.None)
    }

    /**
     * 🔴 **P-06 — « alerte envoyée » avant qu'un seul SMS ne parte.**
     *
     * État exact d'une réservation : `messagesSent = 1`, bail posé, aucun envoi conclu. La
     * notification lisait `messagesSent` et affirmait « 1 message sur 4 envoyé ». Si tous les
     * envois échouaient, le créneau était rendu et la notification disparaissait ; si le processus
     * mourait entre les deux, l'affirmation fausse restait affichée.
     */
    @Test
    fun `un creneau reserve n annonce AUCUN message envoye`() {
        val reserve = armed().copy(
            triggeredAt = ARMED_AT + TIMEOUT,
            messagesSent = 1,
            claimedAt = ARMED_AT + TIMEOUT,
        )
        val notice = decideAt(reserve, elapsed = TIMEOUT)
        assertThat(notice).isEqualTo(
            SafetyCallNotice.Sequence(
                delivered = 0,
                total = SafetyCallConfig.TOTAL_MESSAGES,
                inFlight = true,
            ),
        )
    }

    /** Une fois le bail levé, l'envoi est conclu : on peut enfin dire qu'un message est parti. */
    @Test
    fun `un creneau conclu annonce un message envoye`() {
        val conclu = armed().copy(
            triggeredAt = ARMED_AT + TIMEOUT,
            messagesSent = 1,
            claimedAt = 0L,
        )
        assertThat(decideAt(conclu, elapsed = TIMEOUT)).isEqualTo(
            SafetyCallNotice.Sequence(
                delivered = 1,
                total = SafetyCallConfig.TOTAL_MESSAGES,
                inFlight = false,
            ),
        )
    }

    /**
     * Le **dernier** créneau : `hasRelancePending` devient faux dès la réservation. Sans le terme
     * `isSendInFlight`, la notification aurait disparu pendant l'envoi du dernier message —
     * privant l'utilisateur de son seul moyen de l'arrêter.
     */
    @Test
    fun `le dernier creneau en vol garde la notification affichee`() {
        val dernier = armed().copy(
            triggeredAt = ARMED_AT + TIMEOUT,
            messagesSent = SafetyCallConfig.TOTAL_MESSAGES,
            claimedAt = ARMED_AT + TIMEOUT + 45 * 60 * 1000L,
        )
        assertThat(dernier.hasRelancePending).isFalse() // non-vacuité
        assertThat(decideAt(dernier, elapsed = TIMEOUT)).isEqualTo(
            SafetyCallNotice.Sequence(
                delivered = SafetyCallConfig.TOTAL_MESSAGES - 1,
                total = SafetyCallConfig.TOTAL_MESSAGES,
                inFlight = true,
            ),
        )
    }

    /**
     * 🔴 **Contrat INVERSE par F-01 (relecture Gemini du 2026-08-05).**
     *
     * Ce test affirmait « sequence close, bail leve : plus rien a afficher ». C'etait le defaut :
     * les quatre alertes sont parties chez les proches, et la seule personne a l'ignorer etait
     * celle que ca concernait. La notification reste desormais, jusqu'a ce qu'elle soit vue.
     */
    @Test
    fun `une sequence terminee reste affichee jusqu a ce qu on l acquitte`() {
        val fini = armed().copy(
            triggeredAt = ARMED_AT + TIMEOUT,
            messagesSent = SafetyCallConfig.TOTAL_MESSAGES,
            claimedAt = 0L,
        )
        assertThat(decideAt(fini, elapsed = TIMEOUT)).isEqualTo(
            SafetyCallNotice.Sequence(
                delivered = SafetyCallConfig.TOTAL_MESSAGES,
                total = SafetyCallConfig.TOTAL_MESSAGES,
                inFlight = false,
            ),
        )
    }

    /**
     * Le compteur des envois **conclus** est un dérivé de l'état, et il doit rester exact sur les
     * quatre transitions du protocole de bail.
     */
    @Test
    fun `le compteur des envois conclus suit les transitions du bail`() {
        val base = armed().copy(triggeredAt = ARMED_AT + TIMEOUT)
        assertThat(base.copy(messagesSent = 0, claimedAt = 0L).messagesDelivered).isEqualTo(0)
        // Réservation : le compteur monte, mais rien n'est conclu.
        assertThat(base.copy(messagesSent = 1, claimedAt = 1L).messagesDelivered).isEqualTo(0)
        // Conclusion : le bail tombe, l'envoi compte.
        assertThat(base.copy(messagesSent = 1, claimedAt = 0L).messagesDelivered).isEqualTo(1)
        // Deuxième créneau réservé : un seul envoi conclu jusqu'ici.
        assertThat(base.copy(messagesSent = 2, claimedAt = 1L).messagesDelivered).isEqualTo(1)
        // Jamais négatif, même sur un état incohérent hérité.
        assertThat(base.copy(messagesSent = 0, claimedAt = 1L).messagesDelivered).isEqualTo(0)
    }
}
