package com.filestech.sms.data.sms

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import com.filestech.sms.domain.model.SimActive
import com.filestech.sms.domain.sender.SimsActives
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 2026-11-10 (choix de la SIM d'envoi, v1.30.1) — [SimsActives] lu chez `SubscriptionManager`.
 *
 * La lecture de la liste des SIM qu'utilisent le choix de SIM du composeur, les réglages, la validation à
 * l'envoi et l'étiquette « · SIM 2 » des bulles (`SimLabeler`). La détection de « Mon numéro »
 * (`SettingsScreen.detectMsisdn`) lit encore la liste de son côté, pour la seule SIM SMS d'Android.
 *
 * La permission Téléphone est vérifiée AVANT l'appel binder, plutôt qu'une `SecurityException` attrapée
 * (même choix que `SimLabeler` depuis la v1.22.0).
 */
@Singleton
class SimsActivesAndroid @Inject constructor(
    @ApplicationContext private val context: Context,
) : SimsActives {

    @SuppressLint("MissingPermission")
    override fun lister(): List<SimActive> {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        val infos = runCatching { sm.activeSubscriptionInfoList }
            .onFailure { Timber.w(it, "SimsActives: liste des SIM illisible") }
            .getOrNull()
            .orEmpty()
        // Un emplacement invalide (-1, certains profils eSIM — relecture Claude du 2026-11-10) passe après les
        // emplacements réels et prend le premier numéro libre : jamais « SIM 0 », jamais deux « SIM 1 ».
        val (valides, invalides) = infos.partition { it.simSlotIndex >= 0 }
        val reels = valides.sortedBy { it.simSlotIndex }
            .map { SimActive(it.subscriptionId, it.simSlotIndex, nomDe(it)) }
        var libre = (reels.maxOfOrNull { it.emplacement } ?: -1) + 1
        return reels + invalides.map { SimActive(it.subscriptionId, libre++, nomDe(it)) }
    }

    override fun simSmsDAndroid(): Int? =
        runCatching { SubscriptionManager.getDefaultSmsSubscriptionId() }
            .getOrNull()
            ?.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }

    /** Le nom saisi dans les réglages d'Android, sinon l'opérateur ; `null` si aucun. */
    private fun nomDe(info: SubscriptionInfo): String? =
        info.displayName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: info.carrierName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}
