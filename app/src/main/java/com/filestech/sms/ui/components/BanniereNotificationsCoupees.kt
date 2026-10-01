package com.filestech.sms.ui.components

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.filestech.sms.R
import com.filestech.sms.system.settings.ouvrirLesNotificationsDeLApplication

/**
 * v1.28.13 (balayage des permissions refusées, MR F-Droid !38458) — **une protection qui passe par
 * une notification doit dire quand les notifications sont coupées.**
 *
 * Sans `POST_NOTIFICATIONS` (ou avec les notifications coupées pour l'application), les notifiers
 * sortent sans rien poster, et c'est voulu : `notify()` ne lève pas. Mais rien ne le disait :
 *  - le Safety call perdait son avertissement avant envoi, sa notification d'arrêt, et le signal
 *    « armé mais rien ne partira » posé en v1.28.12 ;
 *  - le raccourci d'urgence de l'écran verrouillé restait affiché « activé » dans les Réglages sans
 *    exister nulle part ;
 *  - les SMS arrivaient sans notification, et l'avertissement des Réglages prévu pour ce cas
 *    (v1.8.0) n'avait jamais été branché.
 *
 * Même patron que [BanniereRoleSmsManquant] : l'état est relu à chaque retour au premier plan, pour
 * que la bannière disparaisse quand on revient de la page Android où l'on vient de les rallumer.
 *
 * @param message ce que la coupure empêche, à cet endroit précis.
 * @param visibles comment lire l'état. Par défaut, les notifications de l'application seules ;
 *   chaque appelant passe en pratique les CANAUX de sa fonction (cf. `CanauxVisibles.kt`), qu'on
 *   coupe un à un dans Android sans toucher à l'interrupteur global.
 */
@Composable
fun BanniereNotificationsCoupees(
    message: String,
    modifier: Modifier = Modifier,
    visibles: (Context) -> Boolean = { NotificationManagerCompat.from(it).areNotificationsEnabled() },
) {
    val contexte = LocalContext.current
    val cycleDeVie = LocalLifecycleOwner.current.lifecycle
    var actives by remember { mutableStateOf(visibles(contexte)) }
    DisposableEffect(cycleDeVie) {
        val observateur = LifecycleEventObserver { _, evenement ->
            if (evenement == Lifecycle.Event.ON_RESUME) actives = visibles(contexte)
        }
        cycleDeVie.addObserver(observateur)
        onDispose { cycleDeVie.removeObserver(observateur) }
    }

    if (actives) return

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
                text = stringResource(R.string.notifications_off_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.size(8.dp))
            Button(onClick = { ouvrirLesNotificationsDeLApplication(contexte) }) {
                Text(stringResource(R.string.notifications_off_fix))
            }
        }
    }
}
