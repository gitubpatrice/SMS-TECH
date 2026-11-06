package com.filestech.sms.system.emergency

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * v1.14.0 — Helper centralisé pour les appels d'urgence.
 * v1.28.12 — les numéros ne sont plus français : ils viennent de [EmergencyNumbers],
 * selon le pays où le téléphone est enregistré.
 *
 * Deux chemins, et la règle d'Android qui les sépare :
 *  - **numéros des secours** → [openDialer] : `ACTION_DIAL`, composeur pré-rempli, l'utilisateur touche
 *    « Appeler ». Aucune permission. C'est TOUT ce qu'Android permet à une application qui n'est pas
 *    l'app Téléphone : un `ACTION_CALL` vers un numéro d'urgence n'est pas passé, Telecom ouvre le
 *    composeur à la place (`NewOutgoingCallIntentBroadcaster` : « Cannot call potential emergency
 *    number … unless caller is system or default dialer », relu dans AOSP et MESURÉ sur l'émulateur
 *    API 34 le 2026-11-06, permission `CALL_PHONE` accordée). L'ancien `placeCall` (v1.14.0) ne passait
 *    donc jamais d'appel : il aboutissait au même composeur après avoir demandé une permission inutile,
 *    et l'écran l'annonçait « Appeler directement ». Supprimé le 2026-11-06.
 *  - **un proche** → [placeTrustedContactCall] : `ACTION_CALL` avec `CALL_PHONE`, appel direct, permis
 *    pour un numéro ordinaire. Refusée : [openTrustedContactDialer].
 *
 * **Sécurité** :
 *  - **Whitelist stricte** des numéros : seuls ceux de [EmergencyNumbers.NUMEROS_AUTORISES]
 *    sont acceptés. Aucun paramètre numéro venant de DataStore, intent extra, ou autre
 *    source non-vérifiable. Si un numéro hors whitelist arrive, fail-safe + Timber.w.
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
 */
object EmergencyCallHelper {

    /**
     * Numéros d'urgence autorisés : l'ensemble FERMÉ de [EmergencyNumbers], c'est-à-dire tous
     * les numéros de sa table, tous pays confondus. L'ensemble reste en dur dans le binaire
     * pour empêcher le chemin d'attaque "intent extra → CALL n'importe quel numéro premium"
     * depuis un caller mal intentionné (BroadcastReceiver exporté par accident).
     *
     * v1.14.1 — ajout 15 (SAMU FR) et 18 (Pompiers FR). 112 reste le SOS EU unifié.
     * v1.28.12 — la liste n'est plus française : quelle tuile s'AFFICHE dépend du pays où le
     * téléphone est enregistré ([EmergencyNumbers.pour]), mais ce que le helper accepte de
     * composer reste cet ensemble fermé, connu à la compilation.
     *
     * Ces numéros sont reconnus par l'OS Android comme "emergency numbers" et peuvent être
     * composés même quand l'écran est verrouillé sur la plupart des devices (comportement OS
     * standard).
     */
    private val ALLOWED_NUMBERS = EmergencyNumbers.NUMEROS_AUTORISES

    enum class CallOutcome {
        /** Appel placé (DIALER ouvert OU CALL_PHONE exécuté). */
        SUCCESS,
        /** Numéro non dans la whitelist. Caller bug. */
        INVALID_NUMBER,
        /** CALL_PHONE non accordée alors qu'on tente l'appel direct d'un proche. */
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
        return dial(context, number)
    }

    /** `ACTION_DIAL` commun à [openDialer] et [openTrustedContactDialer], une fois le numéro validé. */
    private fun dial(context: Context, number: String): CallOutcome {
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:$number")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return runCatching { context.startActivity(intent) }
            .map { CallOutcome.SUCCESS }
            .getOrElse {
                Timber.w(it, "EmergencyCallHelper.dial: no dialer for %s", number)
                CallOutcome.NO_DIALER
            }
    }

    /**
     * v1.14.1 — appel DIRECT à un contact SafetyCall configuré par l'user
     * (`AppSettings.security.safetyCall.contacts`). PAS de whitelist sur
     * le numéro : le contact est sous le contrôle de l'user, persisté en
     * DataStore, pas passé en intent extra. Le caller doit s'assurer que
     * `phoneNumber` vient bien d'un `SafetyCallContact.phoneNumber` lu
     * depuis Settings — JAMAIS d'une source non-contrôlée.
     *
     * Rend PERMISSION_DENIED sans `CALL_PHONE`, OS_ERROR si Android refuse. Le caller se replie
     * alors sur [openTrustedContactDialer] — et non sur [openDialer], qui écarte tout numéro hors
     * de la liste blanche des services d'urgence.
     */
    fun placeTrustedContactCall(context: Context, phoneNumber: String): CallOutcome {
        val cleaned = phoneNumber.trim()
        if (cleaned.isBlank()) {
            Timber.w("EmergencyCallHelper.placeTrustedContactCall: blank number rejected")
            return CallOutcome.INVALID_NUMBER
        }
        return executeCall(context, cleaned)
    }

    /**
     * v1.28.13 — composeur pré-rempli pour un contact SafetyCall : le repli de
     * [placeTrustedContactCall] quand `CALL_PHONE` est refusée. Même contrat sur l'origine du
     * numéro que [placeTrustedContactCall], et aucune permission requise.
     *
     * Il manquait : sans lui, la tuile « Appeler un proche » ne faisait RIEN une fois l'appel
     * direct refusé, alors que les commentaires de l'écran Urgence annonçaient ce repli depuis la
     * v1.14.1.
     */
    fun openTrustedContactDialer(context: Context, phoneNumber: String): CallOutcome {
        val cleaned = phoneNumber.trim()
        if (cleaned.isBlank()) {
            Timber.w("EmergencyCallHelper.openTrustedContactDialer: blank number rejected")
            return CallOutcome.INVALID_NUMBER
        }
        return dial(context, cleaned)
    }

    /**
     * Back-end de [placeTrustedContactCall] : permission, `ACTION_CALL`, try/catch. Réservé aux
     * numéros ordinaires — pour un numéro d'urgence, Android ouvrirait le composeur, cf. KDoc de l'objet.
     */
    private fun executeCall(context: Context, number: String): CallOutcome {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CALL_PHONE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Timber.w("EmergencyCallHelper.placeTrustedContactCall: CALL_PHONE not granted")
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
            Timber.w(se, "EmergencyCallHelper.placeTrustedContactCall: SecurityException")
            CallOutcome.OS_ERROR
        } catch (t: Throwable) {
            Timber.w(t, "EmergencyCallHelper.placeTrustedContactCall: failed")
            CallOutcome.OS_ERROR
        }
    }
}
