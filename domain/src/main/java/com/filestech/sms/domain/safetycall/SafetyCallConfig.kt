package com.filestech.sms.domain.safetycall

import android.os.SystemClock

/**
 * v1.9.0 — Safety call : envoie automatiquement un SMS prédéfini à 1-4
 * contacts d'urgence si l'utilisateur n'a pas ouvert SMS Tech (ni utilisé le
 * bouton "Je vais bien") pendant une durée configurée.
 *
 * Cas d'usage : personnes seules, voyageurs solo, personnes âgées vivant
 * indépendamment, randonneurs, professions à risque. Sécurité personnelle —
 * **pas** un anti-vol (cf. mode panic pour ce cas).
 *
 * **Opt-in strict** : `enabled = false` par défaut. Tant que désactivé, le
 * `SafetyCallWorker` n'est pas schedulé et aucun timer ne court.
 *
 * **Reset du timer** : à chaque ouverture de SMS Tech (`MainActivity.onResume`)
 * ET via le bouton dédié "Je vais bien" dans Settings → Sécurité.
 *
 * **Notification de pré-trigger** : 6h avant l'expiration du timer, SMS Tech
 * pose une notification persistante (canal HIGH) : "Tu n'as pas ouvert SMS
 * Tech depuis [X]. Confirme que tu vas bien." Tap notif = reset timer.
 *
 * **Trigger** : si `lastActivityAt + timeoutMs < now()` au moment du tick
 * worker (toutes les 60 min), envoi atomique du SMS via [SendSmsUseCase] à
 * tous les `contacts` avec le `template` rendu (placeholder `[DURÉE]`
 * remplacé par la valeur effective).
 *
 * **Fail-safe accepté (option A)** : si l'app est désinstallée, le worker
 * est tué par Android et le SMS n'est pas envoyé. C'est cohérent avec le
 * but du deadman (sécurité personnelle, pas anti-vol). Le mode panic gère
 * déjà le vol via wipe.
 *
 * **v1.10.0 SEC-11 — horloge monotone complémentaire** : [isExpired] et
 * [isInWarningWindow] comparent désormais à LA FOIS la wall-clock
 * ([lastActivityAt] vs `System.currentTimeMillis()`) ET la clock monotonic
 * ([monotonicLastActivityAt] vs `SystemClock.elapsedRealtime()`). Un
 * attaquant root capable d'avancer l'horloge OS ne peut plus déclencher
 * prématurément le SMS d'urgence : la clock monotonic n'est pas
 * manipulable sans root + redémarrage. Migration : une config v1.9.0 sans
 * `monotonicLastActivityAt` retourne `isExpired = false` jusqu'au premier
 * reset post-upgrade (filet de sécurité — pas de trigger surprise).
 */
data class SafetyCallConfig(
    /** `false` par défaut. Tant que `false`, aucun worker ni timer actif. */
    val enabled: Boolean = false,
    /**
     * Durée en millisecondes après laquelle le SMS est déclenché si pas
     * d'activité. Valeurs prédéfinies : 24h / 48h / 72h. Custom 1h-720h
     * via slider Setup. Cap absolu : 720h (30 jours) pour rester aligné
     * sur le cas d'usage "voyage solo de 1 mois" sans avoir à reset
     * manuellement le timer en cours de route.
     */
    val timeoutMs: Long = TIMEOUT_48H_MS,
    /**
     * Timestamp epoch ms de la dernière activité enregistrée (= dernier
     * reset). `0L` = pas encore initialisé (l'enable initial pose cette
     * valeur à `now()`).
     *
     * Modifié par :
     *  - [com.filestech.sms.MainActivity.onResume] (auto)
     *  - bouton "Je vais bien" dans Settings → Sécurité (manuel)
     *  - tap notif pré-trigger (depuis [SafetyCallWarningNotifier])
     */
    val lastActivityAt: Long = 0L,
    /**
     * v1.10.0 SEC-11 — Snapshot de `SystemClock.elapsedRealtime()` au moment
     * du même reset que [lastActivityAt]. `0L` = config v1.9.0 héritée ou
     * jamais initialisée. Cette valeur est NON manipulable depuis Settings
     * Android (ne peut être altérée que par root + reboot, ce qui la
     * réinitialise — cf. logique drift post-boot dans
     * [com.filestech.sms.MainApplication]).
     *
     * Modifié EN COUPLE avec [lastActivityAt] à chaque reset (cf. doc
     * ci-dessus). Toute écriture qui pose `lastActivityAt = now()` doit
     * AUSSI poser `monotonicLastActivityAt = SystemClock.elapsedRealtime()`.
     */
    val monotonicLastActivityAt: Long = 0L,
    /**
     * v1.27.2 (audit externe Gemini 2026-08-04) — temps monotone DÉJÀ CAPITALISÉ depuis le
     * dernier reset, en millisecondes.
     *
     * **Le défaut que ce champ ferme.** `SystemClock.elapsedRealtime()` repart de zéro à chaque
     * redémarrage. La récupération de dérive de [com.filestech.sms.MainApplication] ramenait
     * alors [monotonicLastActivityAt] à `nowMono`, ce qui remettait aussi le compteur monotone à
     * zéro. Comme [isExpired] exige que les DEUX horloges aient expiré, il suffisait de
     * redémarrer plus souvent que [timeoutMs] pour que le deadman ne parte JAMAIS — un vol suivi
     * de redémarrages réguliers le neutralisait, et une simple mise à jour système à 20 h sur un
     * délai de 24 h repoussait l'alerte d'autant. Pour une fonction de sécurité personnelle, ne
     * pas partir est le pire des deux échecs possibles.
     *
     * **Comment il est alimenté.** [SafetyCallWorker] jalonne à chaque tick (60 min) : il ajoute
     * ici le segment écoulé et re-cale [monotonicLastActivityAt] sur `nowMono`. La récupération
     * de dérive post-redémarrage ne fait donc plus que re-caler l'ancre — ce champ, lui, conserve
     * tout ce qui a été capitalisé avant. Au pire un redémarrage coûte le segment non encore
     * jalonné, soit moins d'un tick.
     *
     * Remis à `0L` à chaque reset d'activité, **en même temps** que [lastActivityAt] et
     * [monotonicLastActivityAt] — les trois ne se dissocient jamais.
     *
     * Aucune horloge murale n'entre dans ce calcul : la protection contre une avance d'horloge
     * (SEC-11) reste entière.
     */
    val monotonicAccumulatedMs: Long = 0L,
    /**
     * 1 à 4 contacts d'urgence. Plus de 4 = perte de pertinence (un
     * deadman doit cibler les proches qui vont VRAIMENT réagir). Liste
     * vide = config invalide, [enabled] est forcé à `false` au save.
     */
    val contacts: List<SafetyCallContact> = emptyList(),
    /** Template du SMS envoyé. */
    val template: SafetyCallTemplate = SafetyCallTemplate.CHECK_IN,
    /**
     * Si [template] = [SafetyCallTemplate.CUSTOM], texte saisi par l'user.
     * Max 140 chars pour rester dans 1 segment SMS GSM-7 (ou 70 UCS-2 si
     * accents). Ignoré pour les autres templates.
     */
    val customMessage: String = "",
    /**
     * v1.27.2 — horodatage mural du **premier envoi réussi**. `0L` = jamais déclenché.
     *
     * Tant qu'il vaut `0L`, le deadman est en veille et [isExpired] décide seul. Dès qu'il est
     * posé, la séquence de relances prend le relais et [isExpired] rend `false` — sans quoi le
     * message initial repartirait à chaque tick.
     *
     * Horloge **murale** assumée ici, contrairement au décompte avant déclenchement : ce qu'elle
     * protégeait — ne pas partir trop tôt — est déjà joué. Avancer l'horloge à ce stade ne ferait
     * que grouper des relances déjà décidées, jamais en créer.
     */
    val triggeredAt: Long = 0L,
    /**
     * v1.27.2 — nombre de messages **déjà partis** dans la séquence courante : `1` après le
     * message initial, jusqu'à [TOTAL_MESSAGES]. `0` = rien n'est parti.
     *
     * Incrémenté de façon atomique **avant** l'envoi (cf. `TriggerSafetyCallUseCase`) pour qu'un
     * tick périodique et une relance ponctuelle qui se croiseraient n'envoient pas deux fois le
     * même message ; remis à sa valeur d'avant si aucun envoi n'aboutit.
     */
    val messagesSent: Int = 0,
    /**
     * v1.27.2 (relecture Gemini du 2026-08-05) — **bail** sur le créneau réservé : horodatage mural
     * de la réservation, `0L` quand aucun envoi n'est en cours.
     *
     * **Le défaut que ce champ ferme.** `TriggerSafetyCallUseCase` réserve le créneau — incrémente
     * [messagesSent] — **avant** d'envoyer, pour qu'un tick périodique et une relance ponctuelle
     * qui se croiseraient n'envoient pas deux fois. Mais si le processus meurt entre la réservation
     * et l'envoi (mémoire insuffisante, mise à jour du système, batterie critique), le tick suivant
     * lit `messagesSent = 1` et croit le message initial parti : les proches ne reçoivent **jamais**
     * le message qui explique la situation, et découvrent l'affaire par un « Toujours aucun signe de
     * ma part, 15 minutes plus tard ». Ce n'est pas un doublon, c'est une **perte**.
     *
     * Le bail rend la réservation réversible : passé [CLAIM_LEASE_MS] sans conclusion, le créneau
     * est considéré comme abandonné et repris. La fenêtre de perte tombe de « définitive » à « au
     * plus [CLAIM_LEASE_MS] de retard ».
     *
     * ⚠️ Le repli va volontairement vers le **doublon** et non vers la perte : reprendre un créneau
     * dont l'envoi avait en réalité abouti coûte un message en double, ce qui est sans commune
     * mesure avec une alerte muette.
     */
    val claimedAt: Long = 0L,
) {
    /** v1.27.2 — `true` dès que le premier message est parti. */
    val isTriggered: Boolean get() = triggeredAt > 0L

    /**
     * v1.27.2 — `true` si un créneau a été réservé mais jamais conclu, et que le bail a expiré.
     * Le processus a donc été tué entre la réservation et la fin de l'envoi.
     */
    fun isClaimAbandoned(nowMs: Long = System.currentTimeMillis()): Boolean =
        claimedAt > 0L && (nowMs - claimedAt) >= CLAIM_LEASE_MS

    /**
     * v1.27.2 (audit Codex du 2026-08-05, SC-03) — **le seul et unique** moyen d'enregistrer une
     * activité de l'utilisateur.
     *
     * # Le défaut que ça ferme
     *
     * Quatre endroits remettaient le minuteur à zéro : ouverture réelle de l'application, tap sur
     * la notification d'avertissement, bouton « Je vais bien », armement. Ils recopiaient tous les
     * trois champs d'horloge à la main — et **aucun des deux premiers ne clôturait la séquence de
     * relances**. Quelqu'un qui ouvrait l'application après le départ de l'alerte continuait donc
     * à voir ses proches recevoir des relances toutes les quinze minutes, alors qu'il venait très
     * précisément de prouver qu'il allait bien.
     *
     * Le jumeau asymétrique est ici : le bouton « Je vais bien » des Réglages, lui, fermait bien la
     * séquence. Les trois autres non.
     *
     * # Les cinq champs partent ensemble, toujours
     *
     * Les trois horloges (murale, ancre monotone, capital monotone) **ne se dissocient jamais** :
     * garder du capital d'un cycle précédent ferait partir le deadman en avance. `triggeredAt`,
     * `messagesSent` et `claimedAt` ferment la séquence et libèrent tout bail en cours.
     *
     * @param disarmIfTriggered `true` pour le geste explicite « je vais bien » : si l'alerte est
     *   déjà partie, le deadman est **désactivé**, comme demandé par Patrice le 2026-08-05. Une
     *   simple ouverture de l'application se contente, elle, de refermer la séquence et de repartir
     *   pour un cycle — ouvrir son téléphone ne vaut pas renoncer à sa protection.
     */
    fun withActivityReset(
        nowMs: Long = System.currentTimeMillis(),
        nowMonoMs: Long = SystemClock.elapsedRealtime(),
        disarmIfTriggered: Boolean = false,
    ): SafetyCallConfig = copy(
        enabled = if (disarmIfTriggered && isTriggered) false else enabled,
        lastActivityAt = nowMs,
        monotonicLastActivityAt = nowMonoMs,
        monotonicAccumulatedMs = 0L,
        triggeredAt = 0L,
        messagesSent = 0,
        claimedAt = 0L,
    )

    /** v1.27.2 — `true` tant qu'il reste au moins une relance à envoyer. */
    val hasRelancePending: Boolean
        get() = isTriggered && messagesSent in 1 until TOTAL_MESSAGES

    /** v1.27.2 — instant de la prochaine relance, ou `null` s'il n'y en a plus. */
    fun nextRelanceAt(): Long? =
        if (hasRelancePending) triggeredAt + messagesSent * RELANCE_INTERVAL_MS else null

    /** v1.27.2 — `true` quand la prochaine relance est due. */
    fun isRelanceDue(nowMs: Long = System.currentTimeMillis()): Boolean {
        val due = nextRelanceAt() ?: return false
        return nowMs >= due
    }

    /**
     * Retourne `true` quand le timer a expiré du point de vue WALL-CLOCK
     * ET MONOTONIC. Faux si [enabled] = false, si [lastActivityAt] = 0L
     * (jamais initialisé), ou si [monotonicLastActivityAt] = 0L (config
     * v1.9.0 sans monotonic — filet de sécurité post-upgrade).
     *
     * Pourquoi les deux : un attaquant root qui AVANCE la wall-clock OS
     * (`Settings.Global.AUTO_TIME=0` puis `date`) peut faire passer
     * `nowMs - lastActivityAt >= timeoutMs` immédiatement. Mais
     * `SystemClock.elapsedRealtime()` continue à compter le temps réel
     * écoulé depuis le boot, indépendamment de la wall-clock. Les deux
     * checks doivent matcher pour trigger.
     */
    fun isExpired(
        nowMs: Long = System.currentTimeMillis(),
        nowMonoMs: Long = SystemClock.elapsedRealtime(),
    ): Boolean {
        // v1.27.2 — une fois déclenché, le décompte initial est CLOS : c'est la séquence de
        // relances qui prend le relais. Sans cette ligne, `lastActivityAt` n'ayant pas bougé, le
        // message initial repartirait à chaque tick — indéfiniment, puisque le deadman ne se
        // désarme plus tout de suite.
        if (isTriggered) return false
        if (!enabled || lastActivityAt == 0L || monotonicLastActivityAt == 0L) return false
        val wallExpired = (nowMs - lastActivityAt) >= timeoutMs
        val monoExpired = monoElapsedMs(nowMonoMs) >= timeoutMs
        return wallExpired && monoExpired
    }

    /**
     * v1.27.2 (audit externe Gemini 2026-08-04) — temps monotone total écoulé depuis le dernier
     * reset : ce qui a été capitalisé avant le dernier jalon, plus le segment courant.
     *
     * Le `coerceAtLeast(0)` couvre l'intervalle entre un redémarrage et le passage de la
     * récupération de dérive : [monotonicLastActivityAt] y est encore supérieur à `nowMono`, et
     * un segment négatif retrancherait du temps déjà capitalisé — un deadman qui recule. On
     * ignore le segment plutôt que de le soustraire ; la récupération re-calera l'ancre juste
     * après. Le temps déjà capitalisé, lui, n'est jamais perdu.
     *
     * Voir [monotonicAccumulatedMs] pour le motif complet.
     */
    fun monoElapsedMs(nowMonoMs: Long = SystemClock.elapsedRealtime()): Long =
        monotonicAccumulatedMs + (nowMonoMs - monotonicLastActivityAt).coerceAtLeast(0L)

    /**
     * Retourne `true` quand le timer entre dans la fenêtre de pré-trigger
     * (6h avant expiration). Pendant cette fenêtre, SMS Tech pose une
     * notification persistante "Confirme que tu vas bien". Comme pour
     * [isExpired], les deux horloges doivent matcher (cohérence) — un
     * attaquant ne peut donc pas non plus déclencher la notification
     * prématurément.
     */
    fun isInWarningWindow(
        nowMs: Long = System.currentTimeMillis(),
        nowMonoMs: Long = SystemClock.elapsedRealtime(),
    ): Boolean {
        // v1.27.2 — plus d'avertissement une fois le message parti : il annonce un déclenchement
        // qui a déjà eu lieu. C'est la séquence de relances qui informe désormais.
        if (isTriggered) return false
        if (!enabled || lastActivityAt == 0L || monotonicLastActivityAt == 0L) return false
        val elapsedWall = nowMs - lastActivityAt
        // v1.27.2 — même compteur monotone que [isExpired] : sans quoi la fenêtre
        // d'avertissement se serait décalée par rapport au déclenchement qu'elle annonce.
        val elapsedMono = monoElapsedMs(nowMonoMs)
        val window = warningWindowMs()
        val wallInWindow = elapsedWall >= (timeoutMs - window) && elapsedWall < timeoutMs
        val monoInWindow = elapsedMono >= (timeoutMs - window) && elapsedMono < timeoutMs
        return wallInWindow && monoInWindow
    }

    /**
     * v1.27.2 (relecture Gemini du 2026-08-05) — fenêtre d'avertissement **proportionnée** à la
     * durée choisie : un quart du délai, borné entre 15 minutes et 6 heures.
     *
     * **Le défaut que ça ferme.** La fenêtre valait 6 h **en dur**, quelle que soit la durée. Avec
     * le délai d'**une heure** — le minimum que l'interface propose — la condition
     * `écoulé ≥ délai − 6 h` était vraie **dès l'armement** : la notification « Confirme que tu vas
     * bien » s'affichait immédiatement et ne quittait plus la barre d'état. Constaté sur appareil
     * le 2026-08-05. Un avertissement permanent n'avertit plus de rien.
     *
     * Concrètement : 1 h → 15 min (notification à H+45) · 24 h → 6 h (à H+18) · 30 jours → 6 h.
     */
    fun warningWindowMs(): Long =
        (timeoutMs / 4).coerceIn(WARNING_WINDOW_MIN_MS, WARNING_WINDOW_MAX_MS)

    companion object {
        const val TIMEOUT_24H_MS: Long = 24 * 60 * 60 * 1000L
        const val TIMEOUT_48H_MS: Long = 48 * 60 * 60 * 1000L
        const val TIMEOUT_72H_MS: Long = 72 * 60 * 60 * 1000L
        const val TIMEOUT_MIN_MS: Long = 1 * 60 * 60 * 1000L     // 1h
        const val TIMEOUT_MAX_MS: Long = 720 * 60 * 60 * 1000L   // 30 jours

        /**
         * Bornes de la fenêtre de pré-trigger. Voir [warningWindowMs] — elle vaut un quart du
         * délai, ramené dans ces bornes.
         *
         * Le plafond de 6 h est l'ancienne valeur fixe : au-delà, un avertissement posé trop tôt
         * cesse d'être une alerte et devient du décor. Le plancher de 15 min laisse le temps de
         * réagir même sur le délai minimal d'une heure.
         */
        const val WARNING_WINDOW_MAX_MS: Long = 6 * 60 * 60 * 1000L
        const val WARNING_WINDOW_MIN_MS: Long = 15 * 60 * 1000L

        /**
         * v1.27.2 — durée de validité du bail posé sur un créneau réservé. Voir [claimedAt].
         *
         * Deux minutes : assez long pour qu'un envoi vers quatre contacts aboutisse sur un réseau
         * lent sans qu'un tick concurrent ne reprenne le créneau, assez court pour qu'un processus
         * tué ne fasse pas perdre le message initial plus longtemps.
         */
        const val CLAIM_LEASE_MS: Long = 2 * 60 * 1000L

        /**
         * v1.27.2 — nombre de **relances** après le message initial, décidé par Patrice le
         * 2026-08-05.
         *
         * Le déclenchement ne désarme plus le deadman sur-le-champ : si l'application a envoyé
         * l'alerte, c'est que la personne n'a pas donné signe de vie, et un unique SMS peut être
         * manqué — téléphone en silencieux, contact endormi, réseau capricieux.
         *
         * **Borné à trois, volontairement.** La cause la plus probable d'un déclenchement n'est
         * pas un malaise mais une batterie à plat, un voyage sans réseau ou un oubli. Une relance
         * sans fin inonderait des proches sans que personne puisse l'arrêter — celui qui le
         * pourrait est précisément celui qui ne regarde pas son téléphone — et apprendrait aux
         * contacts à ignorer l'alerte le jour où elle est vraie.
         */
        const val RELANCE_COUNT: Int = 3

        /** v1.27.2 — intervalle entre deux messages de la séquence. */
        const val RELANCE_INTERVAL_MS: Long = 15 * 60 * 1000L

        /** v1.27.2 — total envoyé sur une séquence complète : le message initial + les relances. */
        const val TOTAL_MESSAGES: Int = RELANCE_COUNT + 1

        /** Nombre maximum de contacts d'urgence. */
        const val MAX_CONTACTS: Int = 4

        /** Cap du message custom (1 segment SMS UCS-2 sûr avec marge). */
        const val MAX_CUSTOM_MESSAGE_LENGTH: Int = 140
    }
}
