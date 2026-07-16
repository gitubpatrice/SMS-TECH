package com.filestech.sms.ui.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.22.0 (double SIM) — Résout un `subscriptionId` de message en un libellé SIM court et
 * lisible pour l'afficher à côté du nom de l'expéditeur dans le fil de discussion.
 *
 * Le libellé retourné est, dans l'ordre de préférence : le nom que l'utilisateur a donné à la
 * SIM dans les réglages Android ([SubscriptionInfo.displayName]), sinon le nom de l'opérateur
 * ([SubscriptionInfo.carrierName]), sinon `"SIM {slot+1}"`.
 *
 * Retourne `null` — donc **aucun tag affiché** — dans tous les cas où il n'y a rien à
 * distinguer :
 *   - permission `READ_PHONE_STATE` non accordée (l'appel binder est alors évité, pas de
 *     `SecurityException` silencieusement attrapée) ;
 *   - une seule SIM active (mono-SIM : un tag serait du bruit) ;
 *   - `subId` absent ou inconnu (messages historiques importés sans `sub_id`).
 *
 * L'appel binder `activeSubscriptionInfoList` est fait **une seule fois** et mémoïsé au niveau
 * écran, puis la lambda retournée est réutilisée par chaque bulle — pas d'IPC par message.
 */
@Composable
fun rememberSimLabeler(): (Int?) -> String? {
    val context = LocalContext.current
    // `activeSubscriptionInfoList` est un binder IPC qui peut geler le main thread sur Samsung
    // (cf. doctrine projet — SettingsScreen.detectMsisdn, audit Q6) : on le lit sur
    // Dispatchers.IO via produceState. Tant que la lecture n'est pas revenue, la map reste vide
    // → aucun tag (dégradation silencieuse ; pour un simple libellé SIM le micro-délai est
    // invisible et sans flicker gênant).
    val labelsState = produceState(initialValue = emptyMap<Int, String>(), context) {
        value = withContext(Dispatchers.IO) { buildSimLabelMap(context) }
    }
    val labels = labelsState.value
    return remember(labels) {
        { subId: Int? -> if (subId == null) null else labels[subId] }
    }
}

@SuppressLint("MissingPermission")
private fun buildSimLabelMap(context: Context): Map<Int, String> {
    // Check runtime AVANT l'appel binder (cf. detectMsisdn / SettingsScreen) : on rate proprement
    // plutôt que de laisser une SecurityException se faire avaler dans un catch.
    val granted = ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.READ_PHONE_STATE,
    ) == PackageManager.PERMISSION_GRANTED
    if (!granted) return emptyMap()

    val sm = context.getSystemService(SubscriptionManager::class.java) ?: return emptyMap()
    val infos = runCatching { sm.activeSubscriptionInfoList }.getOrNull().orEmpty()
    // Mono-SIM (ou aucune) : rien à distinguer, donc aucun tag.
    if (infos.size < 2) return emptyMap()

    return infos.associate { info ->
        val label = info.displayName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: info.carrierName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: "SIM ${info.simSlotIndex + 1}"
        // `displayName` est un nom saisi librement par l'user dans les réglages Android (non
        // plafonné) : on borne le libellé pour qu'un nom à rallonge ne fasse pas wrapper la
        // bulle sur 2 lignes une fois suffixé au nom de l'expéditeur.
        val capped = if (label.length > MAX_SIM_LABEL_LEN) {
            label.take(MAX_SIM_LABEL_LEN - 1).trimEnd() + "…"
        } else {
            label
        }
        info.subscriptionId to capped
    }
}

/** Longueur max du libellé SIM affiché (au-delà : tronqué + « … »). */
private const val MAX_SIM_LABEL_LEN = 16
