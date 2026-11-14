package com.filestech.sms.system.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.filestech.sms.core.ext.stripMmsAddressSuffix
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.core.util.DernierEffacement
import com.filestech.sms.data.mms.MmsDownloader
import com.filestech.sms.di.ApplicationScope
import com.filestech.sms.system.notifications.MmsFailureNotifier
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 2026-11-14 — **relance le téléchargement d'un MMS reçu** dont le premier essai a échoué, cf. [RelanceMms].
 *
 * Deux portes, une seule suite :
 *  - [ACTION_AUTOMATIQUE], l'alarme posée par [planifier] après un échec passager. Elle ne relance que si la
 *    notification d'échec de ce message est encore affichée : sinon l'utilisateur l'a balayée, ou l'effacement
 *    d'urgence l'a retirée, et l'on n'y touche plus ;
 *  - [ACTION_MANUELLE], le bouton « Réessayer » (notification, ou fenêtre d'explication). La notification est
 *    retirée tout de suite : c'est l'accusé du geste. Si l'essai échoue, `MmsDownloadedReceiver` la repose.
 *
 * Le résultat arrive, comme au premier essai, dans `MmsDownloadedReceiver` : même lecture, même écriture, même
 * réponse au MMSC, même clé de transaction — un message arrivé entre-temps par un autre chemin (l'opérateur peut
 * renvoyer sa notification) est reconnu et n'est pas écrit deux fois.
 *
 * Non exporté, sans filtre : seuls nos propres `PendingIntent`, immuables, l'atteignent. Pas d'`@AndroidEntryPoint`,
 * pour la raison de [MmsWapPushReceiver] (injection Hilt muette au démarrage à froid sur certaines ROM).
 */
class RelanceMmsReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface RelanceMmsEntryPoint {
        fun mmsDownloader(): MmsDownloader

        fun mmsFailureNotifier(): MmsFailureNotifier

        // ⚠️ Résolu DANS la coroutine : il tire `AppDatabase` (liste noire).
        fun incomingBlockPolicy(): IncomingBlockPolicy

        @ApplicationScope
        fun applicationScope(): CoroutineScope
    }

    override fun onReceive(context: Context, intent: Intent) {
        val automatique = when (intent.action) {
            ACTION_AUTOMATIQUE -> true
            ACTION_MANUELLE -> false
            else -> return
        }
        val demande = RelanceMms.Demande.depuis(intent)
        if (demande == null) {
            Timber.w("MMS: nouvel essai sans adresse valide, ignore")
            return
        }
        val entry = try {
            EntryPointAccessors.fromApplication(context.applicationContext, RelanceMmsEntryPoint::class.java)
        } catch (t: Throwable) {
            Timber.e(t, "Hilt entry point resolution failed in RelanceMmsReceiver")
            return
        }
        val scope = entry.applicationScope()
        val pending = goAsync()
        scope.launch {
            try {
                relancerMms(
                    demande = demande,
                    automatique = automatique,
                    notifier = entry.mmsFailureNotifier(),
                    estBloque = { adresse -> entry.incomingBlockPolicy().doitEcarter(adresse) },
                    telecharger = { d ->
                        entry.mmsDownloader().download(
                            contentLocation = d.adresse,
                            transactionId = d.transactionId,
                            senderAddress = d.expediteur,
                            subId = d.subId,
                            transactionIdOctets = d.transactionIdOctets,
                            tentative = d.tentative,
                        )
                    },
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // Comme les autres receveurs MMS : rien ne doit remonter jusqu'au gestionnaire global.
                Timber.w(t, "MMS: nouvel essai en echec")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_AUTOMATIQUE: String = "com.filestech.sms.action.MMS_RETRY_AUTOMATIC"
        const val ACTION_MANUELLE: String = "com.filestech.sms.action.MMS_RETRY_MANUAL"

        /** Le bouton « Réessayer » de [demande]. Un par message : l'URI de [RelanceMms.Demande.identite] les distingue. */
        fun intentionManuelle(context: Context, demande: RelanceMms.Demande): PendingIntent =
            intention(context, ACTION_MANUELLE, demande)

        /**
         * 2026-11-14 (relecture Claude) — le bouton « Réessayer » DÉJÀ créé pour le message [etiquette], ou `null`.
         *
         * La fenêtre d'explication le reçoit ainsi, et non plus comme un `PendingIntent` transmis à `MainActivity` :
         * celle-ci est exportée, et un `PendingIntent` glissé dans son intent, fût-il créé par notre paquet (une
         * appli à qui l'on a donné l'accès aux notifications peut en lire), aurait déclenché une autre de nos
         * actions sous le libellé « Réessayer ». Seule l'étiquette voyage ; le bouton est retrouvé par son identité
         * (action, composant, URI) avec `FLAG_NO_CREATE`, extras d'origine compris. Aucun n'existe : `null`.
         */
        fun boutonExistant(context: Context, etiquette: String): PendingIntent? {
            if (!RelanceMms.etiquetteValide(etiquette)) return null
            val cible = Intent(ACTION_MANUELLE)
                .setClass(context, RelanceMmsReceiver::class.java)
                .setData(RelanceMms.identite(etiquette))
            return PendingIntent.getBroadcast(
                context,
                0,
                cible,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Arme l'essai automatique [demande] dans [delaiMs]. Une alarme par message : la reposer remplace la
         * précédente. Inexacte, permise en veille, sans permission (même choix que `SafetyCallAlarmScheduler`).
         */
        fun planifier(context: Context, demande: RelanceMms.Demande, delaiMs: Long) {
            val alarmes = context.getSystemService(AlarmManager::class.java) ?: return
            runCatching {
                alarmes.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delaiMs,
                    intention(context, ACTION_AUTOMATIQUE, demande),
                )
            }.onSuccess {
                Timber.i("MMS: essai automatique %d dans %d min", demande.tentative, delaiMs / MINUTE_MS)
            }.onFailure {
                // Un OEM peut brider les alarmes : le bouton « Réessayer » reste.
                Timber.w(it, "MMS: essai automatique non arme")
            }
        }

        private fun intention(context: Context, action: String, demande: RelanceMms.Demande): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                demande.dans(Intent(action).setClass(context, RelanceMmsReceiver::class.java)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private const val MINUTE_MS = 60_000L
    }
}

/**
 * 2026-11-14 — la suite d'un nouvel essai, hors du receveur pour être vérifiable sans Hilt (`RelanceMmsSuiteTest`).
 *
 * L'ordre compte :
 *  1. un effacement d'urgence survenu depuis l'échec : rien (`DernierEffacement`) ;
 *  2. la notification de CE message doit être affichée — pour l'essai automatique comme pour le bouton, qu'on ne
 *     touche que tant qu'elle est là (elle ne part plus au tap). Retirée : le message est arrivé, l'utilisateur l'a
 *     balayée, ou l'effacement l'a annulée (relecture Claude du 2026-11-14 : le bouton d'une fenêtre restée ouverte
 *     relançait un message déjà arrivé) ;
 *  3. la liste noire : l'expéditeur a pu être bloqué depuis l'échec (même règle qu'à l'annonce, avant tout
 *     téléchargement ; `TraitementMmsRecu` l'applique de nouveau après) ;
 *  4. un seul essai à la fois par message ([RelanceMms.EnCours]).
 */
@Suppress("LongParameterList")
internal suspend fun relancerMms(
    demande: RelanceMms.Demande,
    automatique: Boolean,
    notifier: MmsFailureNotifier,
    estBloque: suspend (String) -> Boolean,
    telecharger: (RelanceMms.Demande) -> Outcome<Unit>,
    effaceDepuis: (Long) -> Boolean = DernierEffacement::anterieur,
    enCours: RelanceMms.EnCours = RelanceMms.EnCours,
) {
    if (effaceDepuis(demande.lanceeA)) {
        Timber.i("MMS: nouvel essai abandonne, effacement d'urgence depuis l'echec")
        notifier.retirerRelance(demande.etiquette)
        return
    }
    if (!notifier.relanceAffichee(demande.etiquette)) {
        Timber.i(
            "MMS: essai %s %d abandonne, notification retiree",
            if (automatique) "auto" else "manuel",
            demande.tentative,
        )
        return
    }
    val expediteur = demande.expediteur?.stripMmsAddressSuffix()
    if (!expediteur.isNullOrBlank() && estBloque(expediteur)) {
        Timber.i("MMS: nouvel essai abandonne, expediteur bloque")
        notifier.retirerRelance(demande.etiquette)
        return
    }
    if (!enCours.prendre(demande.etiquette)) {
        // L'autre essai rendra son résultat, et `MmsDownloadedReceiver` en tirera la suite.
        Timber.i("MMS: un essai de ce message est deja en cours")
        return
    }
    // Le bouton : la notification part tout de suite, c'est l'accusé du geste ; l'échec la reposera.
    if (!automatique) notifier.retirerRelance(demande.etiquette)
    val res = telecharger(demande)
    // Sans l'adresse : elle peut porter un jeton (audit SEC-06 de `MmsWapPushReceiver`).
    Timber.i(
        "MMS: nouvel essai (%s, tentative %d) -> %s",
        if (automatique) "auto" else "manuel",
        demande.tentative,
        res,
    )
    if (res is Outcome.Failure) {
        // Le téléchargement n'a pas même démarré : aucun résultat ne viendra, l'essai est rendu ici. Le message reste
        // au bouton.
        enCours.rendre(demande.etiquette, lanceA = android.os.SystemClock.elapsedRealtime())
        notifier.notifyFailure(
            reason = MmsFailureNotifier.Reason.DOWNLOAD_FAILED,
            senderAddress = demande.expediteur,
            relance = demande,
        )
    }
}
