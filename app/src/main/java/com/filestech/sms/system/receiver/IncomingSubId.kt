package com.filestech.sms.system.receiver

import android.content.Intent
import android.telephony.SubscriptionManager

/**
 * v1.22.0 — Extrait le `subscriptionId` (SIM d'arrivée) d'un intent entrant
 * `SMS_DELIVER` / `WAP_PUSH_DELIVER` sur un appareil multi-SIM.
 *
 * Deux clés sont tentées, dans l'ordre :
 *   1. [SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX]
 *      (`"android.telephony.extra.SUBSCRIPTION_INDEX"`, API 24+) — la clé moderne et documentée.
 *   2. `"subscription"` (`PhoneConstants.SUBSCRIPTION_KEY` historique) — encore renseignée par de
 *      nombreuses ROM OEM/AOSP anciennes qui ne posent pas encore la clé moderne.
 *
 * Retourne `null` si aucune clé n'est présente ou si la valeur vaut
 * [SubscriptionManager.INVALID_SUBSCRIPTION_ID]. Un `null` fait retomber l'appelant sur son
 * comportement historique (SIM par défaut) — donc **aucune régression** sur mono-SIM ni sur une
 * ROM qui ne renseigne pas ces extras : le pire cas reste exactement le comportement d'avant.
 */
internal fun Intent.extractIncomingSubId(): Int? {
    val modern = getIntExtra(
        SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
        SubscriptionManager.INVALID_SUBSCRIPTION_ID,
    )
    if (modern != SubscriptionManager.INVALID_SUBSCRIPTION_ID) return modern
    @Suppress("DEPRECATION")
    val legacy = getIntExtra("subscription", SubscriptionManager.INVALID_SUBSCRIPTION_ID)
    return legacy.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
}
