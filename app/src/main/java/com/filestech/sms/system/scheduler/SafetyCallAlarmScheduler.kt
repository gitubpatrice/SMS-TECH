package com.filestech.sms.system.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.filestech.sms.domain.safetycall.SafetyCallConfig
import com.filestech.sms.system.receiver.SafetyCallAlarmReceiver
import timber.log.Timber

/**
 * v1.27.2 — **réveil à l'échéance** du Safety call, au lieu du sondage horaire.
 *
 * # Le défaut que ça ferme
 *
 * [SafetyCallWorker] ne programmait rien : il **échantillonnait** toutes les 60 minutes et se
 * demandait à chaque passage « est-ce expiré ? ». Un délai atteint à 14:25 partait donc au premier
 * tick suivant — 14:48 le 2026-08-05, soit **23 minutes de retard**.
 *
 * Ce compromis se défend sur 24 h ou 48 h : 2 à 4 % d'imprécision, invisible. Il ne se défend pas
 * sur **1 h**, qui est pourtant le minimum que l'interface propose — l'attente réelle peut y
 * doubler. L'application offrait un réglage qu'elle ne savait pas honorer.
 *
 * # Pourquoi `setAndAllowWhileIdle` et rien d'autre
 *
 * | API | Permission | Précision |
 * |---|---|---|
 * | `setAndAllowWhileIdle` | **aucune** | à la minute hors Doze, ~10 min en Doze profond |
 * | `setExactAndAllowWhileIdle` | `SCHEDULE_EXACT_ALARM`, à accorder à la main | à la seconde |
 * | `setAlarmClock` | aucune | à la seconde, mais **affiche l'icône réveil** |
 *
 * La deuxième ajoute une permission inquiétante sur une application qui promet la sobriété, et
 * F-Droid publie la liste en clair. La troisième **rend le deadman visible dans la barre d'état** —
 * inacceptable pour une fonction de sécurité personnelle qu'un agresseur ne doit pas repérer.
 * Reste la première, qui traverse le Doze sans rien demander.
 *
 * Le tick horaire de [SafetyCallWorker] **reste** — il n'est pas remplacé mais doublé. C'est lui
 * qui jalonne le compteur monotone, et il rattrape une alarme perdue : les alarmes ne survivent pas
 * à un redémarrage, et certains OEM les effacent à la fermeture forcée.
 *
 * # Un seul point de programmation
 *
 * [sync] est appelé depuis **un unique observateur** de la configuration, dans
 * `MainApplication`. C'était le choix le plus sûr : le Safety call est modifié depuis six endroits
 * (armement, « Je vais bien », ouverture de l'application, tap sur la notification, jalon horaire,
 * envoi d'une relance), et en câbler cinq sur six est exactement le motif de défaut qui a produit
 * la majorité des correctifs de ce mois-ci.
 */
object SafetyCallAlarmScheduler {

    private const val REQUEST_CODE = 0x5AFE

    /**
     * v1.27.2 (audit Codex, C-10) — pas de quantification de l'instant de réveil.
     *
     * Une seconde : au-dessus de la dérive observée entre les deux lectures d'horloge, très en
     * dessous de la précision que le système garantit sur `setAndAllowWhileIdle`.
     */
    private const val QUANTUM_MS = 1_000L

    /**
     * v1.27.2 (audit Codex du 2026-08-05, P-07) — instant du dernier rendez-vous de rattrapage
     * posé, ou `0L`. Voir [apply].
     *
     * ⚠️ **Mémoire de processus, délibérément**, et non un champ persisté : c'est un garde-fou de
     * batterie, pas une garantie fonctionnelle. Une mort du processus la perd, et le pire qui
     * puisse alors arriver est un rattrapage de plus — exactement le comportement d'avant ce
     * correctif. Le filet fonctionnel, lui, reste le tick horaire.
     */
    @Volatile
    private var pastDueRetryAt: Long = 0L

    /**
     * v1.27.2 (audit Codex, C-09) — délai du rendez-vous de rattrapage quand l'échéance est déjà
     * dépassée.
     *
     * Cinq minutes : assez court pour que la ponctualité ne retombe pas sur le tick horaire,
     * assez long pour qu'un rattrapage répété reste indolore en batterie.
     */
    private const val PAST_DUE_RETRY_MS = 5 * 60 * 1000L

    /**
     * Pose l'alarme à [at], ou l'annule si [at] est `null`.
     *
     * ⚠️ **Une échéance déjà dépassée n'est PAS programmée** — et c'est délibéré. Une alarme posée
     * dans le passé se déclenche immédiatement ; le worker s'exécute, ne trouve rien à faire
     * (compteur monotone pas encore écoulé, session leurre, envoi qui échoue), la configuration
     * change au passage, cet observateur repose la même alarme passée, et le cycle recommence :
     * **une boucle de réveil qui viderait la batterie**. J'ai introduit exactement ce défaut dans la
     * première version de ce fichier.
     *
     * Sur une échéance déjà dépassée on retombe donc sur le tick horaire — c'est-à-dire le
     * comportement d'avant ce lot, sans régression. Ce qui est gagné, c'est la ponctualité de
     * toutes les échéances **à venir**, qui sont le cas nominal.
     */
    fun apply(context: Context, at: Long?) {
        if (at == null) {
            cancel(context)
            return
        }
        val now = System.currentTimeMillis()
        if (at <= now) {
            // v1.27.2 (audit Codex du 2026-08-05, C-09) — une échéance DÉJÀ DÉPASSÉE n'est plus
            // simplement abandonnée.
            //
            // On ne peut pas la programmer telle quelle : une alarme posée dans le passé se
            // déclenche immédiatement, le worker ne trouve rien à faire, la configuration change
            // au jalon, l'alarme est reposée — c'est la boucle de réveil que j'avais introduite.
            //
            // Mais l'annuler purement et simplement rendait la ponctualité au tick horaire : un
            // délai d'une heure pouvait encore subir près d'une heure de retard sur ce sous-cas
            // (démarrage à froid, restitution de créneau, alarme perdue par l'OEM).
            //
            // On programme donc un rendez-vous **borné et strictement futur**.
            //
            // v1.27.2 (audit Codex du 2026-08-05, P-07) — 🔴 le commentaire d'origine affirmait ici
            // qu'« une décision inchangée ne repose rien », donc que ce rattrapage ne pouvait pas
            // s'emballer. C'était faux, et de la façon la plus banale : sur un échec total d'envoi,
            // la décision CHANGE deux fois par tentative — la réservation la porte sur l'expiration
            // future du bail, la restitution la ramène sur l'échéance dépassée. Les deux
            // franchissent `distinctUntilChanged`, et chaque retour ici reposait cinq minutes de
            // plus. En mode avion, sans SIM ou sur une téléphonie en panne, l'aller-retour ne
            // s'arrêtait jamais.
            //
            // Un rattrapage déjà armé et encore à venir est donc CONSERVÉ. Le rythme est ainsi
            // borné à un réveil par tranche de cinq minutes, quoi qu'il arrive en amont.
            val armed = pastDueRetryAt
            if (armed > now) {
                Timber.d(
                    "SafetyCallAlarmScheduler: rattrapage deja arme dans %d s, on garde",
                    (armed - now) / 1_000L,
                )
                return
            }
            val retryAt = now + PAST_DUE_RETRY_MS
            pastDueRetryAt = retryAt
            schedule(context, retryAt)
            return
        }
        // Une échéance future réelle remplace le rattrapage : il n'a plus lieu d'être mémorisé.
        pastDueRetryAt = 0L
        schedule(context, at)
    }

    /**
     * Instant du prochain réveil utile, ou `null` s'il n'y en a pas.
     *
     * Fonction **pure**, donc testable sans appareil : c'est elle qui porte toute la décision.
     *
     * Les horloges sont passées explicitement parce que le Safety call compte sur **deux** :
     * la murale et la monotone, et il n'expire que quand les DEUX ont expiré. Ne regarder que la
     * murale poserait l'alarme trop tôt après un redémarrage — le compteur monotone étant en
     * retard, le réveil ne trouverait rien à faire.
     */
    fun nextWakeUpAt(cfg: SafetyCallConfig, nowMs: Long, nowMonoMs: Long): Long? {
        val nominal = when {
            !cfg.enabled -> null
            // Une séquence ouverte a la priorité : la prochaine relance est le seul rendez-vous
            // utile.
            cfg.hasRelancePending -> cfg.nextRelanceAt()
            // Déclenché et séquence close : plus rien à attendre, le désarmement suit.
            cfg.isTriggered -> null
            // Jamais initialisé, ou ancre monotone absente : `isExpired` rend `false` dans les deux
            // cas, donc un réveil ne servirait à rien. L'ancre manquante est réparée au démarrage
            // par `MainApplication`.
            cfg.lastActivityAt == 0L || cfg.monotonicLastActivityAt == 0L -> null
            else -> {
                val wallDeadline = cfg.lastActivityAt + cfg.timeoutMs
                // Ce qu'il reste à courir sur l'horloge monotone, converti en instant mural.
                val monoDeadline = nowMs + (cfg.timeoutMs - cfg.monoElapsedMs(nowMonoMs))
                maxOf(wallDeadline, monoDeadline)
            }
        }
        // v1.27.2 (audit Codex du 2026-08-05, C-05) — L'EXPIRATION DU BAIL DOIT ÊTRE PROGRAMMÉE.
        //
        // Le bail rendait un créneau abandonné *éligible* à la reprise, sans garantir aucun
        // réveil pour la faire : sur les créneaux 1 à 3 le prochain rendez-vous était la relance
        // suivante, quinze minutes plus tard ; sur le dernier, `isTriggered` rendait `null` et
        // plus rien ne venait. Les deux minutes annoncées pouvaient donc en valoir soixante.
        //
        // Tant qu'un créneau est réservé, le prochain instant utile est **au plus tard** son
        // expiration : c'est là qu'on saura si son propriétaire est mort.
        val leaseExpiry = cfg.claimedAt.takeIf { it != 0L }?.plus(SafetyCallConfig.CLAIM_LEASE_MS)
        val at = listOfNotNull(nominal, leaseExpiry).minOrNull() ?: return null
        // v1.27.2 (audit Codex, C-10) — QUANTIFICATION À LA SECONDE.
        //
        // Quand l'échéance monotone domine, l'instant calculé vaut
        // `nowWall + timeout − capital − (nowMono − ancre)`, donc dépend de `nowWall − nowMono`.
        // Les deux horloges sont lues sur deux lignes distinctes : leur écart observé varie de
        // quelques fractions de milliseconde d'une émission à l'autre. `distinctUntilChanged`
        // exigeant une égalité bit à bit, l'invariant « aucune reprogrammation au jalon » n'était
        // pas exact. Arrondir à la seconde l'absorbe, sans rien coûter à la ponctualité — le
        // système ne garantit de toute façon pas mieux que la minute sur ce type d'alarme.
        return ceilToQuantum(at)
    }

    /**
     * v1.27.2 (audit Codex du 2026-08-05, P-03) — arrondit **vers le futur**, jamais vers le passé.
     *
     * # Le défaut que ça ferme
     *
     * La quantification s'écrivait `at / QUANTUM_MS * QUANTUM_MS`, c'est-à-dire un arrondi vers le
     * bas. Une échéance à `T = …999 ms` était donc programmée à `T − 999 ms`. Le système garantit
     * de ne pas livrer **avant l'instant demandé**, mais l'instant demandé était déjà antérieur à
     * l'échéance métier : livrée dans cet intervalle, l'alarme réveillait un worker qui constatait
     * que le deadman n'avait pas encore expiré — ou que le bail n'était pas encore abandonné — et
     * rendait `success()`.
     *
     * 🔴 **L'alarme était alors consommée, et rien ne la reposait** : le collecteur recalcule la
     * même valeur quantifiée, `distinctUntilChanged` supprime l'émission, et la ponctualité
     * retombait sur le tick horaire — jusqu'à une heure plus tard. Sur le dernier créneau, c'est
     * précisément le cas que la programmation de l'expiration du bail voulait fermer.
     *
     * Une seconde de trop est sans conséquence : le système ne garantit pas mieux que la minute sur
     * `setAndAllowWhileIdle`. Une milliseconde de moins coûtait une heure.
     */
    private fun ceilToQuantum(at: Long): Long {
        val floored = Math.floorDiv(at, QUANTUM_MS) * QUANTUM_MS
        return if (floored == at) at else floored + QUANTUM_MS
    }

    private fun schedule(context: Context, atMs: Long) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context)
        // v1.27.2 (audit Codex du 2026-08-05, SC-06) — **pas de `cancel` préalable.**
        //
        // `setAndAllowWhileIdle` remplace déjà toute alarme portant le même `PendingIntent`. Le
        // `cancel` explicite n'apportait rien et ouvrait une fenêtre : une mort du processus entre
        // les deux supprimait l'ancienne alarme **sans poser la nouvelle**, laissant le deadman
        // sans réveil jusqu'au prochain tick horaire.
        runCatching {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
        }.onSuccess {
            Timber.i(
                "SafetyCallAlarmScheduler: reveil pose dans %d min",
                (atMs - System.currentTimeMillis()) / 60_000L,
            )
        }.onFailure {
            // Aucune permission n'est requise, mais un OEM peut brider les alarmes d'une
            // application mise en veille. On ne masque pas l'échec : le tick horaire reste le
            // filet, et il faut pouvoir lire dans les journaux que la ponctualité est perdue.
            Timber.w(it, "SafetyCallAlarmScheduler: alarme refusee, repli sur le tick horaire")
        }
    }

    /**
     * v1.27.2 — repose un réveil dans [delayMs] quand un tick a été **supprimé sans rien décider**.
     *
     * Le seul appelant est la garde panic-decoy de [SafetyCallWorker] : si l'alarme d'échéance
     * sonne pendant une session leurre, le worker se retire sans rien envoyer et l'alarme est
     * **consommée**. Sans ce rappel, on retomberait sur le tick horaire — donc sur le défaut que
     * ce lot corrige — juste après une session sous contrainte, c'est-à-dire précisément quand
     * l'alerte compte le plus.
     */
    fun retryIn(context: Context, delayMs: Long) {
        // Ce rendez-vous remplace physiquement l'alarme : le rattrapage mémorisé ne décrit plus
        // ce qui est réellement armé, il ne doit donc plus servir à supprimer une programmation.
        pastDueRetryAt = 0L
        schedule(context, System.currentTimeMillis() + delayMs)
    }

    private fun cancel(context: Context) {
        pastDueRetryAt = 0L
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        manager.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, SafetyCallAlarmReceiver::class.java)
            .setAction(SafetyCallAlarmReceiver.ACTION_SAFETY_CALL_DUE)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
