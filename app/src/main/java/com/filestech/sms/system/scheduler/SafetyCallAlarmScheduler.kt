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
     * Aligne l'alarme sur [cfg] : la pose à la bonne échéance, ou l'annule s'il n'y a plus rien à
     * attendre. Idempotent — une alarme déjà posée au même instant est simplement remplacée.
     */
    fun sync(context: Context, cfg: SafetyCallConfig) {
        val at = nextWakeUpAt(cfg)
        if (at == null) {
            cancel(context)
            return
        }
        schedule(context, at)
    }

    /**
     * Instant du prochain réveil utile, ou `null` s'il n'y en a pas.
     *
     * Fonction **pure**, donc testable sans appareil : c'est elle qui porte toute la décision.
     */
    fun nextWakeUpAt(cfg: SafetyCallConfig): Long? = when {
        !cfg.enabled -> null
        // Une séquence ouverte a la priorité : la prochaine relance est le seul rendez-vous utile.
        cfg.hasRelancePending -> cfg.nextRelanceAt()
        // Déclenché et séquence close : plus rien à attendre, le désarmement suit.
        cfg.isTriggered -> null
        // Jamais initialisé — l'armement posera `lastActivityAt` et repassera par ici.
        cfg.lastActivityAt == 0L -> null
        else -> cfg.lastActivityAt + cfg.timeoutMs
    }

    private fun schedule(context: Context, atMs: Long) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context)
        // `setAndAllowWhileIdle` remplace déjà toute alarme portant le même PendingIntent ; le
        // `cancel` explicite couvre le cas où la précédente aurait été posée par une version
        // antérieure avec des extras différents.
        manager.cancel(pending)
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

    private fun cancel(context: Context) {
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
