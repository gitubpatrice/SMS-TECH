package com.filestech.sms.system.emergency

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * v1.14.0 — Helper centralisé pour les appels d'urgence 112 / 17.
 *
 * Deux flows possibles selon `EmergencyCallBehavior` :
 *  - `DIALER_ONLY` → [openDialer] : `ACTION_DIAL` ouvre le composeur pré-rempli,
 *    l'user confirme par tap "vert". Pas de permission requise. Default.
 *  - `HOLD_3S_DIRECT_CALL` → [placeCall] : `ACTION_CALL` avec `CALL_PHONE`
 *    permission, appel direct sans confirmation système. Le hold-3s est
 *    déclenché côté UI via un `EmergencyHoldButton` qui anti-pocket-dial.
 *
 * **Sécurité** :
 *  - **Whitelist stricte** des numéros : seuls "112" et "17" acceptés. Aucun
 *    paramètre numéro venant de DataStore, intent extra, ou autre source
 *    non-vérifiable. Si un numéro hors whitelist arrive, fail-safe + Timber.w.
 *  - `Intent.FLAG_ACTIVITY_NEW_TASK` posé défensivement (le caller peut être
 *    BroadcastReceiver via EmergencyShortcutReceiver).
 *  - `try/catch` sur SecurityException + ActivityNotFoundException : pas de
 *    crash si l'OS refuse au runtime.
 *  - Retourne [Outcome] explicite pour que l'UI affiche un snackbar
 *    différentié (PERMISSION_DENIED vs NO_DIALER vs SUCCESS).
 *
 * **Hors scope** :
 *  - Pas d'enregistrement de l'appel (RECORD_AUDIO réservé voice MMS).
 *  - Pas de log persistant des appels (rien dans Room, juste Timber).
 *  - Pas de détection automatique du pays (numéros EU=112 + FR=17 hardcodés).
 */
object EmergencyCallHelper {

    /**
     * Numéros d'urgence autorisés. Hardcodés ici pour empêcher tout chemin
     * d'attaque "intent extra → CALL n'importe quel numéro premium" depuis
     * un caller mal intentionné (BroadcastReceiver exported par accident).
     */
    private val ALLOWED_NUMBERS = setOf("112", "17")

    enum class CallOutcome {
        /** Appel placé (DIALER ouvert OU CALL_PHONE exécuté). */
        SUCCESS,
        /** Numéro non dans la whitelist. Caller bug. */
        INVALID_NUMBER,
        /** CALL_PHONE non accordée alors qu'on tente placeCall. */
        PERMISSION_DENIED,
        /** Aucun dialer installé (rare, stripped AOSP / corporate MDM). */
        NO_DIALER,
        /** SecurityException ou autre échec OS. */
        OS_ERROR,
    }

    /**
     * `ACTION_DIAL` — composeur ouvert pré-rempli avec [number]. L'user tape
     * "vert" pour confirmer l'appel. Aucune permission requise. **Default
     * v1.12-v1.14**, zéro risque pocket-dial.
     */
    fun openDialer(context: Context, number: String): CallOutcome {
        if (number !in ALLOWED_NUMBERS) {
            Timber.w("EmergencyCallHelper.openDialer: rejected non-whitelisted number %s", number)
            return CallOutcome.INVALID_NUMBER
        }
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:$number")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return runCatching { context.startActivity(intent) }
            .map { CallOutcome.SUCCESS }
            .getOrElse {
                Timber.w(it, "EmergencyCallHelper.openDialer: no dialer for %s", number)
                CallOutcome.NO_DIALER
            }
    }

    /**
     * `ACTION_CALL` — appel DIRECT via CALL_PHONE permission. À utiliser
     * UNIQUEMENT après hold-3s côté UI (anti-pocket-dial). Requires
     * `CALL_PHONE` runtime permission accordée.
     *
     * Retourne `PERMISSION_DENIED` si la permission n'est pas accordée — le
     * caller doit fallback en `openDialer` et afficher un warning UX.
     */
    fun placeCall(context: Context, number: String): CallOutcome {
        if (number !in ALLOWED_NUMBERS) {
            Timber.w("EmergencyCallHelper.placeCall: rejected non-whitelisted number %s", number)
            return CallOutcome.INVALID_NUMBER
        }
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CALL_PHONE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Timber.w("EmergencyCallHelper.placeCall: CALL_PHONE not granted for %s", number)
            return CallOutcome.PERMISSION_DENIED
        }
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$number")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            CallOutcome.SUCCESS
        } catch (se: SecurityException) {
            Timber.w(se, "EmergencyCallHelper.placeCall: SecurityException for %s", number)
            CallOutcome.OS_ERROR
        } catch (t: Throwable) {
            Timber.w(t, "EmergencyCallHelper.placeCall: failed for %s", number)
            CallOutcome.OS_ERROR
        }
    }
}
