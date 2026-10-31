package com.filestech.sms.ui.security

import android.app.Activity
import android.os.Build
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi

/**
 * La barre de sélection ne remet pas le texte sélectionné à une autre application.
 *
 * Cette barre s'ouvre sur **toute** sélection, dans un champ ou dans un `SelectionContainer`
 * (sélection du texte d'un message, coffre compris). Deux portes y menaient hors de l'application,
 * depuis une application qui ne détient pas la permission Internet.
 *
 * 1. **Le classifieur de texte d'Android.** Sur un téléphone équipé des applications Google, un appui
 *    long sur un mot propose **« Traduire »**, en tête de barre : un toucher remet le texte à
 *    l'application Google, qui le cherche EN LIGNE. Compose interroge le classifieur du contexte de
 *    l'écran, cette activité, par son [TextClassificationManager] ; un dialogue enveloppe ce contexte
 *    et lit le même gestionnaire. [TextClassifier.NO_OP], posé ici, couvre tous les champs de toutes
 *    les fenêtres de l'activité ; le gestionnaire de l'application garde le classifieur du système.
 *    Mesuré et fermé dans Notes Tech 3.1.1.
 * 2. **Les actions de texte des autres applications** (`ACTION_PROCESS_TEXT`). Compose ajoute à la
 *    barre chaque application VISIBLE qui en déclare une — n'importe laquelle peut le faire, sous
 *    n'importe quel nom. À partir d'Android 11, la visibilité des paquets les cache, sauf les
 *    applications visibles de tous (TalkBack et sa lecture à voix haute) : d'où le retrait du
 *    `<queries>` du manifeste, qui les rendait toutes visibles. Avant Android 11, rien ne les cache :
 *    [ComposeFoundationFlags.isSmartSelectionEnabled] à `false` retire de la barre ces actions et la
 *    sélection intelligente, pour tout Compose et tout le processus.
 *
 * Ce qui se perd : la sélection intelligente (un appui long qui sélectionne une adresse entière), les
 * actions du classifieur (appeler, ouvrir une carte, traduire) et, avant Android 11 seulement, la
 * lecture à voix haute dans la barre (elle reste joignable par son propre raccourci d'accessibilité).
 * Couper, Copier, Coller et Tout sélectionner restent.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Activity.keepSelectedTextInApp() {
    getSystemService(TextClassificationManager::class.java)?.setTextClassifier(TextClassifier.NO_OP)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        ComposeFoundationFlags.isSmartSelectionEnabled = false
    }
}
