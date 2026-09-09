package com.filestech.sms.data.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.sender.SmsSender
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.28.3 (F23) — `requestCode` d'un `PendingIntent` de suivi, extrait pour être **mesurable**.
 *
 * Ce que la fonction doit garantir, et qui ne se lit pas dans un `PendingIntent` :
 *  - deux TENTATIVES du même message ne partagent plus le même code. Sans cela,
 *    `PendingIntent.filterEquals` — qui ignore les extras — les tenait pour un seul et même
 *    objet, `FLAG_UPDATE_CURRENT` réécrivait les extras du premier, et rien dans l'accusé ne
 *    disait de quelle tentative il venait ;
 *  - deux PARTIES d'un même envoi restent distinctes (audit F36) ;
 *  - deux MESSAGES restent distincts, y compris au-delà de `Int.MAX_VALUE`.
 *
 * La tentative est mêlée par XOR sur un multiple impair dérivé du nombre d'or, et non par une
 * multiplication supplémentaire : `mix` occupe déjà les bits hauts, et l'y décaler encore
 * rapprocherait les identifiants que F36 avait justement séparés.
 */
internal fun smsTrackingRequestCode(action: String, localId: Long, partIndex: Int, attempt: Int): Int {
    val mix = (localId xor (localId ushr 32)) * SmsSenderImpl.MAX_PARTS + partIndex.toLong()
    val salt = (attempt.toLong() + 1L) * SmsSenderImpl.GOLDEN_ODD
    return ((mix xor salt) xor action.hashCode().toLong()).toInt() and 0x7FFFFFFF
}

/**
 * Wraps the Android [SmsManager] API. Caller is expected to be the default SMS app.
 *
 * Pending intent extras encode the local Room message id so receivers can update the DB.
 */
@Singleton
class SmsSenderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wireFormatter: PhoneNumberWireFormatter,
) : SmsSender {

    override fun send(
        localMessageId: Long,
        destination: String,
        text: String,
        subId: Int?,
        requestDeliveryReport: Boolean,
        attempt: Int,
    ): Outcome<Unit> {
        return try {
            val manager = subscriptionAwareManager(subId)
            // Normalise the destination to E.164 for the wire so a foreign SIM (e.g. a Luxembourg
            // SIM texting a French `06…`) can route it. Falls back to `destination` verbatim when
            // the region is unknown or the number is a short code — no regression on domestic
            // sends. Only the wire address is affected; the Room/provider rows keep the raw form.
            val wireDestination = wireFormatter.toWireFormat(destination, subId)
            val parts = manager.divideMessage(text)
            val sentIntents = ArrayList<PendingIntent>(parts.size)
            val deliveredIntents = ArrayList<PendingIntent>(parts.size)
            for (i in parts.indices) {
                sentIntents += buildPendingIntent(ACTION_SMS_SENT, localMessageId, i, parts.size, attempt)
                deliveredIntents += buildPendingIntent(ACTION_SMS_DELIVERED, localMessageId, i, parts.size, attempt)
            }
            manager.sendMultipartTextMessage(
                wireDestination,
                /* scAddress = */ null,
                parts,
                sentIntents,
                if (requestDeliveryReport) deliveredIntents else null,
            )
            Outcome.Success(Unit)
        } catch (t: Throwable) {
            Outcome.Failure(AppError.Telephony("sendMultipartTextMessage failed", t))
        }
    }

    /**
     * Resolves the right [SmsManager] for the given [subId] (multi-SIM support). On API ≥ 31
     * (S) we route through the system-service factory; on older releases we fall back to the
     * legacy static helpers. `SmsManager.getDefault()` itself is deprecated everywhere, so the
     * `subId == null` branch also prefers the system-service form when available.
     */
    private fun subscriptionAwareManager(subId: Int?): SmsManager {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val sm = context.getSystemService(SmsManager::class.java)
            return if (subId == null) sm else sm.createForSubscriptionId(subId)
        }
        return when {
            subId == null -> @Suppress("DEPRECATION") SmsManager.getDefault()
            else -> @Suppress("DEPRECATION") SmsManager.getSmsManagerForSubscriptionId(
                if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    SubscriptionManager.getDefaultSmsSubscriptionId()
                } else subId,
            )
        }
    }

    private fun buildPendingIntent(
        action: String,
        localId: Long,
        partIndex: Int,
        total: Int,
        attempt: Int,
    ): PendingIntent {
        // Audit F36: derive the request code from a 64-bit mix so two distinct (localId, partIndex)
        // pairs cannot collide on Int wraparound when localId > Int.MAX_VALUE / MAX_PARTS.
        //
        // Audit P0-2 (v1.2.0): Intent **explicit** via `setClass`. The previous `setPackage`-only
        // implicit form was silently dropped on Android 14+ — the SmsSent / SmsDelivered receivers
        // never fired → outgoing SMS rows stuck in PENDING forever (only the 15 min watchdog in
        // TelephonySyncWorker promoted them to FAILED, hiding the real bug). Explicit targeting
        // also removes any need for an `<intent-filter>` on the receiver, keeping it `exported=false`.
        // Composant ciblé par NOM (string) et non `::class.java` : les receivers vivent dans le
        // module `:app` (system/), inaccessible depuis `:data`. `setClassName(context, fqcn)` cible
        // le même composant explicite à l'exécution (package = context.packageName, classe = fqcn),
        // en debug comme en release — le suffixe `.debug` ne change pas le package de la classe.
        val targetClassName = when (action) {
            ACTION_SMS_SENT -> "com.filestech.sms.system.receiver.SmsSentReceiver"
            ACTION_SMS_DELIVERED -> "com.filestech.sms.system.receiver.SmsDeliveredReceiver"
            else -> throw IllegalArgumentException("unknown action: $action")
        }
        //
        // v1.28.3 (F23) — la TENTATIVE entre dans l'identité, pas seulement dans les extras.
        //
        // Une relance réutilise le même id Room. `PendingIntent.filterEquals` ignorant les
        // extras, la tentative 2 retrouvait donc le `PendingIntent` de la tentative 1 — même
        // action, même composant, même `requestCode` — et `FLAG_UPDATE_CURRENT` se contentait de
        // réécrire ses extras. Les deux tentatives partageaient une seule adresse de retour, et
        // rien dans l'accusé ne disait de laquelle il venait. En la mêlant au `requestCode`,
        // chaque tentative obtient le sien, et celui de la précédente reste distinct au lieu
        // d'être écrasé : son accusé tardif arrive toujours, mais **identifié**, donc écartable.
        val intent = Intent(action).setClassName(context, targetClassName).apply {
            putExtra(EXTRA_LOCAL_ID, localId)
            putExtra(EXTRA_PART_INDEX, partIndex)
            putExtra(EXTRA_PART_COUNT, total)
            putExtra(EXTRA_ATTEMPT, attempt)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val request = smsTrackingRequestCode(action, localId, partIndex, attempt)
        return PendingIntent.getBroadcast(context, request, intent, flags)
    }

    companion object {
        const val ACTION_SMS_SENT = "com.filestech.sms.action.SMS_SENT"
        const val ACTION_SMS_DELIVERED = "com.filestech.sms.action.SMS_DELIVERED"
        const val EXTRA_LOCAL_ID = "com.filestech.sms.extra.LOCAL_ID"
        const val EXTRA_PART_INDEX = "com.filestech.sms.extra.PART_INDEX"
        const val EXTRA_PART_COUNT = "com.filestech.sms.extra.PART_COUNT"

        /**
         * v1.28.3 (F23) — numéro de la tentative d'envoi porté par l'accusé.
         *
         * Absent des `PendingIntent` créés par une version antérieure et encore en vol au moment
         * de la mise à jour : les receveurs lisent alors `0`, qui est bien le numéro de tentative
         * des lignes existantes après la migration 10 → 11. L'accusé s'applique donc normalement.
         */
        const val EXTRA_ATTEMPT = "com.filestech.sms.extra.ATTEMPT"

        internal const val MAX_PARTS = 256L

        /** Multiplicateur impair dérivé du nombre d'or, pour disperser le numéro de tentative. */
        internal const val GOLDEN_ODD = -0x61c8_8647L
    }
}
