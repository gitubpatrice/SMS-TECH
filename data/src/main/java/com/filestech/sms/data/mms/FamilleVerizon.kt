package com.filestech.sms.data.mms

import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/**
 * 2026-11-06 (courriel d'un utilisateur Visible/Verizon, Galaxy A54) — **les opérateurs dont le MMSC attend du client
 * ce qu'Android n'ajoute pas seul.**
 *
 * La configuration Verizon d'AOSP (`carrier_config_carrierid_1839_Verizon-Wireless.xml`) porte `enabledTransID`,
 * `enabledNotifyWapMMSC` et `httpParams` ; celle de Visible, opérateur virtuel du même réseau, n'en porte aucun, et
 * la configuration la plus précise l'emporte sans fusion. Les applications SMS libres qui reçoivent sur Visible
 * (QUIK, Fossify Messages, par la bibliothèque `android-smsmms`) embarquent donc ces réglages par MCC/MNC de la SIM
 * (`res/xml-mcc…-mnc…/mms_config.xml`) : ce sont ces listes et ces valeurs, recopiées telles quelles.
 */
internal object FamilleVerizon {

    /** Verizon : 310 004, 310 005, 311 012 et 311 480 (dont Visible). */
    private val VERIZON: Set<String> = setOf("310004", "310005", "311012", "311480")

    /** US Cellular, qui attend les deux mêmes réglages et un autre en-tête : 311 220 à 229, 311 580 à 589. */
    private val US_CELLULAR: Set<String> = buildSet {
        for (mnc in 220..229) add("311$mnc")
        for (mnc in 580..589) add("311$mnc")
    }

    /**
     * En-têtes HTTP au format de `SmsManager.MMS_CONFIG_HTTP_PARAMS`. Le service MMS d'Android remplace lui-même
     * `##LINE1NOCOUNTRYCODE##` par le numéro de la ligne : l'application ne le lit pas, et il ne part que vers le
     * MMSC de l'opérateur.
     */
    const val EN_TETES_VERIZON = "x-up-calling-line-id: 1##LINE1NOCOUNTRYCODE##|X-VzW-MDN: 1##LINE1NOCOUNTRYCODE##"
    const val EN_TETES_US_CELLULAR = "x-vzw-mdn: 1##LINE1NOCOUNTRYCODE##"

    /**
     * La SIM (MCC+MNC, `TelephonyManager.simOperator`) est-elle de la famille ? Son MMSC attend alors l'identifiant de
     * transaction en fin d'adresse ([AdresseDeTelechargement]) et la réponse `m-notifyresp.ind` à cette adresse
     * (`enabledTransID`, `enabledNotifyWapMMSC`).
     */
    fun contient(operateurSim: String?): Boolean = operateurSim in VERIZON || operateurSim in US_CELLULAR

    /** Les en-têtes HTTP de cet opérateur, ou `null` hors de la famille. */
    fun enTetesHttp(operateurSim: String?): String? = when (operateurSim) {
        in VERIZON -> EN_TETES_VERIZON
        in US_CELLULAR -> EN_TETES_US_CELLULAR
        else -> null
    }
}

/**
 * 2026-11-06 — MCC+MNC de la SIM de [subId] (`311480`…), ou `null` ; aucune permission requise. Une lecture qui lève
 * (ROM, mode avion en transition) rend `null` : l'appelant se comporte alors comme hors de [FamilleVerizon].
 */
internal fun operateurSim(context: Context, subId: Int?): String? = runCatching {
    val base = context.getSystemService(TelephonyManager::class.java) ?: return null
    val tm = if (subId != null && subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
        base.createForSubscriptionId(subId)
    } else {
        base
    }
    tm.simOperator?.takeIf { it.isNotBlank() }
}.getOrNull()
