package com.filestech.sms.ui.components

import android.Manifest
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.filestech.sms.system.settings.ouvrirLaFicheDeLApplication
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

/**
 * Audit pré-release 1.28.13 (M2), jumeau du correctif de la tuile 112 — **un bouton « Autoriser » ne
 * reste jamais sans effet.** Après deux refus, Android n'affiche plus la boîte de dialogue et répond
 * « refusé » sur-le-champ : « Autoriser la localisation » et « Autoriser la position exacte » ne
 * faisaient alors plus rien, sans un mot. Rien dans l'API ne distingue « refusé à l'instant » de
 * « refusé sans demander » ; le seul signe observable est la DURÉE. Une réponse sans la position
 * exacte arrivée en moins de [SEUIL_SANS_DIALOGUE_MS] n'a pas pu passer par un humain : on ouvre alors
 * la fiche Android de l'application, où la permission reste accordable. C'est une heuristique,
 * assumée : au pire, une fiche s'ouvre pour qui a refusé en moins de quatre dixièmes de seconde.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun rememberPermissionLocalisation(onResultat: (accordee: Boolean) -> Unit = {}): PermissionLocalisation {
    val contexte = LocalContext.current
    val debutDeLaDemande = remember { mutableLongStateOf(0L) }
    val etat = rememberMultiplePermissionsState(
        permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        onPermissionsResult = { reponses ->
            val instantanee = SystemClock.elapsedRealtime() - debutDeLaDemande.longValue < SEUIL_SANS_DIALOGUE_MS
            if (instantanee && reponses[Manifest.permission.ACCESS_FINE_LOCATION] != true) {
                ouvrirLaFicheDeLApplication(contexte)
            }
            onResultat(reponses.values.any { it })
        },
    )
    val exacte = etat.permissions.any {
        it.permission == Manifest.permission.ACCESS_FINE_LOCATION && it.status == PermissionStatus.Granted
    }
    return PermissionLocalisation(
        accordee = etat.permissions.any { it.status == PermissionStatus.Granted },
        exacte = exacte,
        demander = {
            debutDeLaDemande.longValue = SystemClock.elapsedRealtime()
            etat.launchMultiplePermissionRequest()
        },
    )
}

/** Une boîte de dialogue affichée puis refusée par un humain prend bien plus longtemps que cela. */
private const val SEUIL_SANS_DIALOGUE_MS = 400L
