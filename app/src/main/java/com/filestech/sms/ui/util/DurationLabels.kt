package com.filestech.sms.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.filestech.sms.R

/**
 * v1.28.12 — le libellé d'une durée, dans la langue de l'application.
 *
 * ⚠️ Il était construit À LA MAIN, en français, à DEUX endroits — l'écran des réglages
 * (« Restant : 3 jours avant déclenchement ») et le dialogue de durée personnalisée
 * (« 96 h ≈ 4 jours »). Un lecteur allemand lisait donc « Noch 3 jours », du français
 * injecté dans un cadre traduit. Aucun contrôle de parité ne pouvait le voir : ces mots
 * n'étaient pas dans `strings.xml`.
 *
 * Le plus vexant est que les pluriels existaient déjà, dans les cinq langues, et étaient
 * employés correctement deux fichiers plus loin par `AndroidSafetyMessageTexts`. Le bon
 * patron était à côté ; ces deux écrans ne l'empruntaient pas. Relevé par deux audits
 * indépendants le 2026-09-17.
 *
 * Une seule aide pour les deux appelants, plutôt qu'un correctif posé sur chacun : c'est
 * la divergence entre chemins jumeaux qui a produit le défaut, la refermer en deux
 * exemplaires la rouvrirait au prochain écran.
 *
 * [heures] est une durée en heures, supposée positive.
 */
@Composable
fun libelleDeDuree(heures: Int): String {
    if (heures < HEURES_PAR_JOUR) {
        return pluralStringResource(R.plurals.safety_duration_hours, heures, heures)
    }
    val jours = heures / HEURES_PAR_JOUR
    val resteEnHeures = heures % HEURES_PAR_JOUR
    return if (resteEnHeures == 0) {
        pluralStringResource(R.plurals.safety_duration_days, jours, jours)
    } else {
        stringResource(R.string.duration_days_hours, jours, resteEnHeures)
    }
}

private const val HEURES_PAR_JOUR = 24
