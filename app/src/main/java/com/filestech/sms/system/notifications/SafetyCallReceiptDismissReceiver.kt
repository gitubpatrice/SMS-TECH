package com.filestech.sms.system.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * v1.27.4 — **le balayage du reçu de fin de séquence vaut « j'ai vu »**.
 *
 * # Le défaut fermé ici
 *
 * [SafetyCallWarningNotifier] posait ses notifications avec `setOngoing(true)` et le commentaire
 * « non swipable — l'user DOIT taper pour reset ». **Cette affirmation était périmée** : depuis
 * Android 14, une notification `ongoing` se balaie. Et aucun `setDeleteIntent` n'existait.
 *
 * Conséquence constatée par Patrice sur son S24 (Android 16) le 2026-08-06 : il balaie le reçu de
 * fin de séquence — « je la swape avec le doigt, je vais pas réactiver » — l'affichage disparaît,
 * **aucun état ne change**, et la notification est republiée au démarrage à froid suivant, parce que
 * [com.filestech.sms.domain.safetycall.SafetyCallNotice.decide] rend toujours
 * `Sequence(terminal = true)` tant que le cycle n'est pas archivé.
 *
 * # Pourquoi ce récepteur n'existe QUE pour l'état terminal
 *
 * C'est le point sensible, et il faut le dire explicitement : **un `deleteIntent` sur
 * l'avertissement de pré-déclenchement, ou sur une séquence en cours, serait un coupe-circuit du
 * deadman atteignable depuis le volet, écran verrouillé, sans le code de l'application.** C'est
 * exactement l'attaque que [SafetyCallWarningNotifier.rearmActivityIntent] documente déjà pour
 * refuser un `BroadcastReceiver` sur l'action « Réactiver ». Le tap du corps, lui, passe par
 * `startActivity` : le système exige un déverrouillage. Un récepteur, non.
 *
 * Dans l'état **terminal**, cet argument tombe — et lui seul :
 *
 *  - la séquence est allée au bout, les messages sont partis ;
 *  - `enabled` est **déjà** `false`, le désarmement étant écrit dans la transaction du dernier
 *    envoi ;
 *  - aucune alarme n'est programmée.
 *
 * Acquitter ne peut donc **rien** affaiblir : il n'existe plus de protection à couper. Le seul
 * effet est d'archiver un cycle achevé, ce que le tap du corps fait déjà. La notification est un
 * reçu, et un reçu se balaie.
 *
 * ⚠️ Cette asymétrie est le cœur du correctif. La reproduire à l'identique sur les deux autres
 * états serait une régression de sécurité, pas une amélioration de cohérence.
 *
 * # Pourquoi aucun nonce ici, contrairement au tap et au réarmement
 *
 * Le récepteur est déclaré `android:exported="false"` : aucune application tierce ne peut lui
 * envoyer quoi que ce soit. Le nonce de [SafetyCallIntentToken] existe parce que `MainActivity` est
 * `exported="true"` (rôle SMS) et qu'un intent y est forgeable ; l'argument ne se transporte pas.
 *
 * Surtout, en poser un ici serait **nuisible** : `consume` est mono-usage et `rotate` invalide le
 * précédent. Un jeton propre à cet intent invaliderait celui du corps et du bouton « Réactiver » —
 * le défaut SC-1273-04 déjà corrigé une fois dans ce fichier.
 *
 * # Ce qui garantit qu'un retrait programmatique ne déclenche pas ce chemin
 *
 * `NotificationManager.cancel()` **ne déclenche pas** le `deleteIntent` : seul un rejet explicite de
 * l'utilisateur le fait (balayage, ou « Tout effacer »). [SafetyCallWarningNotifier.reconcile]
 * retire donc ses notifications sans jamais passer ici — sans quoi chaque réconciliation aurait
 * archivé le cycle qu'elle venait d'afficher.
 *
 * Et si les deux gestes se produisaient quand même, l'écriture est **idempotente** :
 * `withActivityReset` remet `triggeredAt` à zéro et `historyWithCurrentCycle` n'archive rien quand
 * il n'y a plus de cycle courant.
 */
@AndroidEntryPoint
class SafetyCallReceiptDismissReceiver : BroadcastReceiver() {

    @Inject lateinit var settings: SettingsRepository

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SAFETY_CALL_RECEIPT_SEEN) return
        // ⚠️ Pas de garde `appLock` ici, contrairement à [NotificationActionReceiver].
        //
        // Sa garde existe parce qu'y répondre **envoie un SMS au nom de l'utilisateur** : c'est
        // l'écriture qui est dangereuse, pas la lecture. Ici rien ne part, rien ne se désactive —
        // `enabled` est déjà faux — et exiger un déverrouillage pour balayer un reçu rendrait le
        // geste impossible depuis le volet, c'est-à-dire là où il est fait.
        val pending = goAsync()
        scope.launch {
            try {
                settings.update { s ->
                    val cfg = s.security.safetyCall
                    // Rien à acquitter : l'utilisateur a balayé une notification déjà réconciliée
                    // par ailleurs (tap, Réglages, réarmement). Ne pas écrire évite de faire
                    // avancer `generation` pour rien, ce qui invaliderait des workers vivants.
                    if (!cfg.isTriggered) return@update s
                    s.copy(
                        security = s.security.copy(
                            // Exactement le geste du tap du corps : archive le cycle, referme,
                            // repart d'un minuteur neuf. `disarmIfTriggered` est sans effet ici
                            // puisque `enabled` est déjà faux — il est passé pour que les deux
                            // chemins soient littéralement le même appel, et qu'une divergence
                            // future doive être écrite explicitement.
                            safetyCall = cfg.withActivityReset(disarmIfTriggered = true),
                        ),
                    )
                }
                Timber.i("SafetyCallReceiptDismissReceiver: receipt acknowledged by swipe")
            } catch (t: kotlinx.coroutines.CancellationException) {
                // ⚠️ Jamais avalée : `scope` est annulable et une annulation n'est pas un échec.
                throw t
            } catch (t: Throwable) {
                // L'écriture a échoué : le cycle reste ouvert, donc la notification sera republiée
                // au prochain démarrage à froid. C'est le comportement d'AVANT ce correctif —
                // l'échec ramène à l'état antérieur, jamais à une protection silencieusement
                // perdue.
                Timber.w(t, "SafetyCallReceiptDismissReceiver: ack write failed — receipt will return")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** Action interne, jamais exportée — voir la note sur l'absence de nonce. */
        const val ACTION_SAFETY_CALL_RECEIPT_SEEN = "com.filestech.sms.SAFETY_CALL_RECEIPT_SEEN"
    }
}
