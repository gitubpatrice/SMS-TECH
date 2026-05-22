package com.filestech.sms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import com.filestech.sms.domain.smishing.SmishingReason
import com.filestech.sms.ui.theme.BrandDanger

/**
 * v1.11.0 — Sujet 3 anti-smishing : bandeau rouge "⚠️ Possiblement frauduleux"
 * affiché sous une bulle de message entrant détecté comme suspect par
 * [com.filestech.sms.domain.smishing.SmishingDetector].
 *
 * Cliquable → ouvre un [SmishingExplainDialog] qui liste les raisons en
 * français/anglais. L'user décide librement d'ignorer le bandeau (il
 * n'empêche pas la lecture du SMS ni l'ouverture des liens), mais
 * l'information est posée AVANT que la personne tappe sur un lien dans
 * la précipitation.
 *
 * **Design** : fond rouge marque [BrandDanger] + texte blanc — cohérent
 * avec tous les éléments destructifs/d'alerte dans l'app (boutons nuke,
 * snackbar erreur, etc.). Forme arrondie pour rester soft visuellement
 * et ne pas créer de "carré rouge" qui agresse l'œil.
 */
@Composable
fun SmishingBanner(
    reasons: List<SmishingReason>,
    modifier: Modifier = Modifier,
) {
    if (reasons.isEmpty()) return
    var explainOpen by remember { mutableStateOf(false) }
    // v1.11.0 audit U4 — retour haptique au tap : alerte de sécurité =
    // signal sensoriel cohérent avec le pattern Files Tech (cf. mode
    // urgence HoldButton + boutons destructifs Settings).
    val haptic = LocalHapticFeedback.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(BrandDanger, shape = RoundedCornerShape(8.dp))
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                explainOpen = true
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(8.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.smishing_banner_title),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = stringResource(R.string.smishing_banner_subtitle),
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (explainOpen) {
        SmishingExplainDialog(reasons = reasons, onDismiss = { explainOpen = false })
    }
}

/**
 * Dialog "Pourquoi cet avertissement ?" affiché au tap sur [SmishingBanner].
 * Liste les raisons détectées avec une explication courte chacune. Aucun
 * texte ne fait référence au contenu du SMS — l'user apprend les patterns,
 * pas le verdict spécifique.
 */
@Composable
private fun SmishingExplainDialog(
    reasons: List<SmishingReason>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.smishing_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.smishing_dialog_intro))
                reasons.forEach { reason ->
                    Text(
                        text = "• " + stringResource(reasonStringRes(reason)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.smishing_dialog_dismiss))
            }
        },
    )
}

private fun reasonStringRes(reason: SmishingReason): Int = when (reason) {
    SmishingReason.UrlShortener -> R.string.smishing_reason_url_shortener
    SmishingReason.UrgencyKeyword -> R.string.smishing_reason_urgency
    SmishingReason.PremiumNumber -> R.string.smishing_reason_premium
    SmishingReason.TyposquattedDomain -> R.string.smishing_reason_typosquatting
}
