package com.filestech.sms.ui.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.filestech.sms.data.sms.SimsActivesAndroid
import com.filestech.sms.domain.model.SimActive
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

/**
 * 2026-11-10 (v1.30.1) — les SIM actives, lues hors du fil principal par la même lecture que les étiquettes
 * ci-dessus. Vide tant que la lecture n'est pas revenue, ou si la permission Téléphone est refusée.
 */
@Composable
fun rememberSimsActives(): List<SimActive> {
    val context = LocalContext.current
    return produceState(initialValue = emptyList<SimActive>(), context) {
        value = withContext(Dispatchers.IO) { SimsActivesAndroid(context.applicationContext).lister() }
    }.value
}

private fun buildSimLabelMap(context: Context): Map<Int, String> {
    // 2026-11-10 (v1.30.1) — la liste vient de [SimsActivesAndroid], la seule lecture des SIM de
    // l'application (permission vérifiée avant l'appel binder, comme ici depuis la v1.22.0).
    val sims = SimsActivesAndroid(context.applicationContext).lister()
    // Mono-SIM (ou aucune) : rien à distinguer, donc aucun tag.
    if (sims.size < 2) return emptyMap()
    // `nom` est saisi librement dans les réglages d'Android (non plafonné) : borné pour qu'un nom à
    // rallonge ne fasse pas passer la bulle sur deux lignes une fois suffixé au nom de l'expéditeur.
    return sims.associate { sim -> sim.subId to borner(sim.nom ?: "SIM ${sim.emplacement + 1}") }
}

/**
 * 2026-11-10 (v1.30.1) — le libellé d'une SIM dans le choix de SIM (composeur, réglages) : « SIM 2 · Orange ».
 *
 * Le numéro d'emplacement vient d'abord, parce que c'est ainsi que l'utilisateur désigne ses cartes
 * (« la SIM 2 ») ; le nom suit quand Android en donne un.
 */
fun libelleDeSim(sim: SimActive): String {
    val numero = "SIM ${sim.emplacement + 1}"
    val nom = sim.nom?.takeIf { !it.equals(numero, ignoreCase = true) } ?: return numero
    return "$numero · ${borner(nom)}"
}

private fun borner(libelle: String): String =
    if (libelle.length > MAX_SIM_LABEL_LEN) libelle.take(MAX_SIM_LABEL_LEN - 1).trimEnd() + "…" else libelle

/** Longueur max du libellé SIM affiché (au-delà : tronqué + « … »). */
private const val MAX_SIM_LABEL_LEN = 16
