package com.filestech.sms.ui.screens.settings

import androidx.annotation.StringRes
import com.filestech.sms.R

/**
 * 2026-10-29 — ce que le récapitulatif du mode urgence armé (Réglages) dit de la position : ce que
 * le SMS d'urgence emportera VRAIMENT, pas seulement ce que le réglage demande.
 *
 * Il lisait `includeLocation` seul et affichait « Position GPS incluse » même sans permission de
 * localisation — le SMS partait alors sans coordonnées (observé le 2026-10-04, corrigé ici). Mêmes
 * trois cas que `EmergencyScreen` et `EmergencySetupScreen`, qui avertissaient déjà : permission
 * refusée, position approximative (zone d'environ 2 km), position exacte.
 */
@StringRes
internal fun libelleDePositionArmee(inclure: Boolean, accordee: Boolean, exacte: Boolean): Int = when {
    !inclure -> R.string.settings_emergency_armed_no_location
    !accordee -> R.string.settings_emergency_armed_location_denied
    !exacte -> R.string.settings_emergency_armed_location_approximate
    else -> R.string.settings_emergency_armed_location
}
