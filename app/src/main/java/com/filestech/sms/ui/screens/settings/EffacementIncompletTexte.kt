package com.filestech.sms.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.filestech.sms.R

/**
 * v1.28.9 (septième note d'Andrew, MR !38458, constat 2) — ce qu'un effacement incomplet a laissé,
 * dit cause par cause.
 *
 * Le dialogue ne connaissait qu'une cause : des copies restées dans la messagerie du téléphone. Une
 * liste de conversations illisible passait pour vide — « Rien ne reviendra » — et un fichier, une
 * clé ou le magasin sécurisé qui résistaient ne se disaient pas. Chaque cause a sa phrase, parce que
 * chacune appelle un geste différent.
 *
 * Mêmes mots en session normale et en session leurre : une différence observable serait la fuite
 * que le leurre existe pour empêcher. Sorti de [SettingsScreen], dont la complexité est au seuil de
 * detekt.
 */
@Composable
internal fun EffacementIncompletTexte(resultat: SettingsViewModel.Event.DataWiped) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (resultat.listeIllisible) {
            Text(stringResource(R.string.settings_nuke_list_unreadable))
        }
        if (resultat.restantes > 0) {
            Text(pluralStringResource(R.plurals.settings_nuke_residue_body, resultat.restantes, resultat.restantes))
        }
        if (resultat.echecsLocaux > 0) {
            Text(stringResource(R.string.settings_nuke_local_failure))
        }
    }
}
