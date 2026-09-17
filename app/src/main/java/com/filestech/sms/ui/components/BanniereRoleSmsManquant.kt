package com.filestech.sms.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.filestech.sms.R

/**
 * v1.28.12 — **une fonction de sécurité qui ne peut pas envoyer doit le dire AVANT qu'on compte
 * dessus.**
 *
 * `SendSmsUseCase` refuse tout envoi si l'application n'est pas l'application SMS par défaut, et
 * le Safety call comme le mode urgence passent par là. Les deux écrans d'armement n'en disaient
 * **rien** : on pouvait armer un homme-mort, voir « activé » et un compte à rebours, et n'être
 * protégé par rien. À l'échéance, l'envoi échouait, la réservation était rendue, et aucune
 * notification n'était posée — le silence complet, indéfiniment.
 *
 * Relevé par un audit de motifs le 2026-09-17. L'application savait pourtant formuler ce refus :
 * elle l'affiche avant la purge des données (`settings_nuke_confirm_not_default`), et l'état du
 * rôle figure dans les Réglages. Jamais à l'endroit où l'on arme une protection.
 *
 * ⚠️ L'état est **relu à chaque retour au premier plan**, pas une fois en composition : sans cela,
 * la bannière resterait affichée après que l'utilisateur soit allé accorder le rôle, et lui dirait
 * qu'il n'est pas protégé alors qu'il l'est — ce qui userait sa confiance dans l'avertissement.
 *
 * @param aLeRole comment lire l'état du rôle. Appelé à la composition et à chaque `ON_RESUME`.
 * @param onCorriger ouvre le sélecteur système d'application SMS par défaut.
 */
@Composable
fun BanniereRoleSmsManquant(
    aLeRole: () -> Boolean,
    onCorriger: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cycleDeVie = LocalLifecycleOwner.current.lifecycle
    var roleDetenu by remember { mutableStateOf(aLeRole()) }
    DisposableEffect(cycleDeVie) {
        val observateur = LifecycleEventObserver { _, evenement ->
            if (evenement == Lifecycle.Event.ON_RESUME) roleDetenu = aLeRole()
        }
        cycleDeVie.addObserver(observateur)
        onDispose { cycleDeVie.removeObserver(observateur) }
    }

    if (roleDetenu) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.safety_role_missing_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.safety_role_missing_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.size(8.dp))
            Button(onClick = onCorriger) {
                Text(stringResource(R.string.settings_set_default))
            }
        }
    }
}
