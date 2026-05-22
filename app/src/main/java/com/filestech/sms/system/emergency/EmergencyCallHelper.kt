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
     *
     * v1.14.1 — ajout 15 (SAMU FR) et 18 (Pompiers FR). 112 reste le SOS
     * EU unifié. Tous les numéros français sont 24/7/365 gratuits. Ils
     * sont reconnus par l'OS Android comme "emergency numbers" et peuvent
     * être composés même quand l'écran est verrouillé sur la plupart des
     * devices (comportement OS standard).
     */
    private val ALLOWED_NUMBERS = setOf("15", "17", "18", "112")

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
     * `ACTION_CALL` — appel DIRECT via CALL_PHONE permission. Whitelist
     * stricte aux numéros d'urgence officiels (112 / 15 / 17 / 18) :
     * un numéro hors whitelist est rejeté AVANT toute interaction avec
     * `startActivity`. Anti redirect via Intent extra forgé.
     *
     * Retourne `PERMISSION_DENIED` si la permission n'est pas accordée — le
     * caller doit fallback en `openDialer` et afficher un warning UX.
     */
    fun placeCall(context: Context, number: String): CallOutcome {
        if (number !in ALLOWED_NUMBERS) {
            Timber.w("EmergencyCallHelper.placeCall: rejected non-whitelisted number %s", number)
            return CallOutcome.INVALID_NUMBER
        }
        return executeCall(context, number, isWhitelistedEmergency = true)
    }

    /**
     * v1.14.1 — appel DIRECT à un contact SafetyCall configuré par l'user
     * (`AppSettings.security.safetyCall.contacts`). PAS de whitelist sur
     * le numéro : le contact est sous le contrôle de l'user, persisté en
     * DataStore, pas passé en intent extra. Le caller doit s'assurer que
     * `phoneNumber` vient bien d'un `SafetyCallContact.phoneNumber` lu
     * depuis Settings — JAMAIS d'une source non-contrôlée.
     *
     * Même flow PERMISSION_DENIED / OS_ERROR que [placeCall]. Caller doit
     * gérer fallback `openDialer` si refusé.
     */
    fun placeTrustedContactCall(context: Context, phoneNumber: String): CallOutcome {
        val cleaned = phoneNumber.trim()
        if (cleaned.isBlank()) {
            Timber.w("EmergencyCallHelper.placeTrustedContactCall: blank number rejected")
            return CallOutcome.INVALID_NUMBER
        }
        return executeCall(context, cleaned, isWhitelistedEmergency = false)
    }

    /**
     * Common back-end: permission check + ACTION_CALL intent + try/catch.
     * Le paramètre `isWhitelistedEmergency` n'a aucun effet runtime — il
     * sert uniquement à différencier les logs en cas d'erreur.
     */
    private fun executeCall(
        context: Context,
        number: String,
        isWhitelistedEmergency: Boolean,
    ): CallOutcome {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CALL_PHONE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Timber.w("EmergencyCallHelper.placeCall: CALL_PHONE not granted (emergency=%s)", isWhitelistedEmergency)
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
            Timber.w(se, "EmergencyCallHelper.placeCall: SecurityException (emergency=%s)", isWhitelistedEmergency)
            CallOutcome.OS_ERROR
        } catch (t: Throwable) {
            Timber.w(t, "EmergencyCallHelper.placeCall: failed (emergency=%s)", isWhitelistedEmergency)
            CallOutcome.OS_ERROR
        }
    }
}
