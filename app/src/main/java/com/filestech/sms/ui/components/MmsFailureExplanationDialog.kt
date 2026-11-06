package com.filestech.sms.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.sms.R
import com.filestech.sms.data.mms.PdusEnAttente
import com.filestech.sms.system.notifications.MmsFailureExplanationHolder
import com.filestech.sms.system.notifications.MmsFailureNotifier
import timber.log.Timber

/**
 * 2026-11-06 — la fenêtre ouverte par un tap sur la notification « MMS non reçu ».
 *
 * Elle dit ce qui s'est passé et ce que l'utilisateur peut faire, sans rien promettre que l'application
 * ne tienne : aucun réglage MMS n'existe, et aucun nouvel essai n'est possible pour un message refusé ou
 * dont le téléchargement a échoué — son adresse n'est gardée nulle part. Seul un fichier non enregistré
 * est repris, par `RepriseMmsWorker`.
 *
 * Mêmes gardes que [com.filestech.sms.ui.AppRoot] applique à la confirmation Safety call, et pour les
 * mêmes raisons :
 *  - **session leurre** : le porteur est effacé, rien ne s'affiche ;
 *  - **application verrouillée** : la fenêtre attend le déverrouillage ;
 *  - **péremption** : tranchée quand la fenêtre PEUT s'ouvrir, pas au tap ; une fois parue, elle ne périme
 *    plus ([MmsFailureExplanationHolder.marquerAffichee]) et reste jusqu'à ce que l'utilisateur la ferme ;
 *  - le porteur n'est vidé qu'à la FERMETURE : une rotation pendant l'affichage la réaffiche.
 */
@Composable
fun MmsFailureExplanationOverlay(
    holder: MmsFailureExplanationHolder,
    showLock: Boolean,
    isPanicDecoy: Boolean,
) {
    val pending by holder.pending.collectAsStateWithLifecycle()
    val current = pending ?: return
    // Calculée quand la fenêtre devient affichable (verrou levé). Une fenêtre déjà parue ne périme plus.
    val expire = remember(current, showLock) { current.isExpired() }
    LaunchedEffect(current, isPanicDecoy, expire) {
        if (isPanicDecoy || expire) holder.clear()
    }
    if (isPanicDecoy || showLock || expire) return
    // Clé du TAP (motif et instant), pas du marquage : marquer change `current`, et ne doit pas relancer l'effet.
    LaunchedEffect(current.reason, current.postedAt) { holder.marquerAffichee() }
    MmsFailureExplanationDialog(reason = current.reason, erreur = current.erreur, onDismiss = { holder.consume() })
}

@Composable
internal fun MmsFailureExplanationDialog(
    reason: MmsFailureNotifier.Reason,
    erreur: MmsFailureNotifier.ErreurAndroid?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val body = when (reason) {
        MmsFailureNotifier.Reason.TOO_LARGE -> stringResource(
            R.string.mms_failure_dialog_body_too_large,
            (PdusEnAttente.PLAFOND_OCTETS / OCTETS_PAR_MIO).toInt(),
        )
        MmsFailureNotifier.Reason.DOWNLOAD_FAILED -> stringResource(R.string.mms_failure_dialog_body_download_failed)
        MmsFailureNotifier.Reason.MEDIA_NOT_SAVED -> stringResource(R.string.mms_failure_dialog_body_media_not_saved)
        MmsFailureNotifier.Reason.CARRIER_ERROR -> stringResource(R.string.mms_failure_dialog_body_carrier_error)
    }
    // 2026-11-06 — le code qu'Android a rendu pour un téléchargement manqué, à nous transmettre : seulement des
    // entiers validés (`MmsFailureNotifier.ErreurAndroid.valide`), dans une phrase fixe. Pour aucun autre motif.
    val ligneErreur = when {
        erreur == null || reason != MmsFailureNotifier.Reason.DOWNLOAD_FAILED -> null
        erreur.statutHttp != null ->
            stringResource(R.string.mms_failure_dialog_android_error_http, erreur.code, erreur.statutHttp)
        else -> stringResource(R.string.mms_failure_dialog_android_error, erreur.code)
    }
    // Un bouton seulement là où il mène à quelque chose d'utile : le réseau pour un téléchargement manqué, le
    // stockage pour un fichier non enregistré. Trop volumineux, refusé par l'opérateur : rien à régler ici.
    val reglages = when (reason) {
        MmsFailureNotifier.Reason.DOWNLOAD_FAILED ->
            stringResource(R.string.mms_failure_dialog_network_settings) to Settings.ACTION_WIRELESS_SETTINGS
        MmsFailureNotifier.Reason.MEDIA_NOT_SAVED ->
            stringResource(R.string.mms_failure_dialog_storage_settings) to Settings.ACTION_INTERNAL_STORAGE_SETTINGS
        MmsFailureNotifier.Reason.TOO_LARGE, MmsFailureNotifier.Reason.CARRIER_ERROR -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mms_failure_notification_title)) },
        // Défilant : le texte compte plusieurs phrases, et la fenêtre doit tenir à grande police.
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(body)
                ligneErreur?.let { Text(it, modifier = Modifier.padding(top = 12.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
        dismissButton = reglages?.let { (libelle, action) ->
            {
                TextButton(
                    onClick = {
                        ouvrirReglages(context, action)
                        onDismiss()
                    },
                ) { Text(libelle) }
            }
        },
    )
}

/**
 * La page de réglages [action], sinon les Réglages tout court. `ACTION_WIRELESS_SETTINGS` et
 * `ACTION_INTERNAL_STORAGE_SETTINGS` existent sur toutes les versions prises en charge ; le panneau Internet
 * d'Android 10 et plus doit être lancé « pour résultat » et varie selon les ROM. Une ROM sans aucune des deux
 * pages ne fait rien : la fenêtre se ferme.
 */
private fun ouvrirReglages(context: Context, action: String) {
    for (essai in listOf(action, Settings.ACTION_SETTINGS)) {
        try {
            context.startActivity(Intent(essai))
            return
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "no activity for %s", essai)
        }
    }
}

private const val OCTETS_PAR_MIO = 1024L * 1024L
