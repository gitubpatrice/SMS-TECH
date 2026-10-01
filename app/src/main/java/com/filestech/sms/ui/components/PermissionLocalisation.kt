package com.filestech.sms.ui.components

import android.Manifest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberMultiplePermissionsState

/**
 * v1.28.13 — **la position du mode urgence se demande en exacte ET approximative, et l'approximative
 * compte.**
 *
 * Les trois écrans qui la demandaient (écran Urgence, réglages du mode urgence, Réglages) ne
 * réclamaient et ne testaient que `ACCESS_FINE_LOCATION`. Or depuis Android 12, la boîte de dialogue
 * propose « Approximative » : l'utilisateur qui la choisissait accordait `ACCESS_COARSE_LOCATION`
 * seule, l'écran affichait « Permission Localisation non accordée » et le SMS d'urgence partait sans
 * position — mesuré sur émulateur API 34. Les deux permissions sont désormais demandées ensemble,
 * comme le prescrit la documentation Android, et lues au même endroit pour les trois écrans.
 *
 * @property accordee une position, exacte ou approximative, peut être lue.
 * @property exacte `ACCESS_FINE_LOCATION` : le GPS. Sans elle, le SMS annonce sa marge.
 */
@Stable
class PermissionLocalisation internal constructor(
    val accordee: Boolean,
    val exacte: Boolean,
    val demander: () -> Unit,
)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun rememberPermissionLocalisation(onResultat: (accordee: Boolean) -> Unit = {}): PermissionLocalisation {
    val etat = rememberMultiplePermissionsState(
        permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        onPermissionsResult = { reponses -> onResultat(reponses.values.any { it }) },
    )
    val exacte = etat.permissions.any {
        it.permission == Manifest.permission.ACCESS_FINE_LOCATION && it.status == PermissionStatus.Granted
    }
    return PermissionLocalisation(
        accordee = etat.permissions.any { it.status == PermissionStatus.Granted },
        exacte = exacte,
        demander = etat::launchMultiplePermissionRequest,
    )
}
