package com.filestech.sms.ui.screens.thread

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.SimCard
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import com.filestech.sms.domain.model.SimActive
import com.filestech.sms.ui.util.libelleDeSim

/**
 * 2026-11-10 (v1.30.1) — la SIM d'envoi, au-dessus du champ de saisie : « SIM 2 · Free ▾ ». Un appui ouvre
 * la liste des SIM ; le choix vaut pour la visite du fil, et se retrouve ensuite sur le message envoyé, donc
 * à la visite suivante (cf. `ChoixDeSim.pourConversation`).
 *
 * N'affiche RIEN avec moins de deux SIM : un téléphone à une SIM n'a rien à choisir, et le composeur reste
 * celui d'avant la 1.30.1.
 *
 * Une demande d'un utilisateur à deux SIM (2026-11-10) : SMS Tech envoyait toujours par la SIM 1, sans
 * moyen d'en changer, alors que l'écran « À propos » et `PERMISSIONS.md` annonçaient le choix.
 */
@Composable
internal fun ChoixDeSimComposeur(
    sims: List<SimActive>,
    simEnvoi: Int?,
    onChoisir: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sims.size < 2) return
    val actuelle = sims.firstOrNull { it.subId == simEnvoi } ?: return
    var ouvert by remember { mutableStateOf(false) }
    Box(modifier.padding(start = 8.dp, top = 4.dp)) {
        AssistChip(
            onClick = { ouvert = true },
            label = {
                Text(libelleDeSim(actuelle), maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            leadingIcon = {
                Icon(
                    Icons.Outlined.SimCard,
                    contentDescription = null,
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            },
            trailingIcon = {
                // Lu par TalkBack après le libellé : « SIM 2 · Free, changer de SIM, bouton ».
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = stringResource(R.string.thread_sim_change),
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            },
            modifier = Modifier.testTag(TAG_CHOIX_SIM),
        )
        DropdownMenu(expanded = ouvert, onDismissRequest = { ouvert = false }) {
            sims.forEach { sim ->
                DropdownMenuItem(
                    text = { Text(libelleDeSim(sim)) },
                    onClick = {
                        ouvert = false
                        onChoisir(sim.subId)
                    },
                    leadingIcon = {
                        if (sim.subId == actuelle.subId) {
                            Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.thread_sim_selected))
                        }
                    },
                    modifier = Modifier.testTag(TAG_SIM_PREFIXE + sim.emplacement),
                )
            }
        }
    }
}

/** La pastille de la SIM d'envoi, pour les tests. */
internal const val TAG_CHOIX_SIM = "choix_sim"

/** Une SIM de la liste, suivie de son emplacement (`choix_sim_0` = SIM 1), pour les tests. */
internal const val TAG_SIM_PREFIXE = "choix_sim_"
