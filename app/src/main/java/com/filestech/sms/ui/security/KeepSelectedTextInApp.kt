package com.filestech.sms.ui.security

import android.app.Activity
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
 *    n'importe quel nom. La visibilité des paquets ne suffit pas à les écarter : avant Android 11 elle
 *    n'existe pas, et ensuite elle se donne par application ENTIÈRE — le `<queries>` `SEND
 *    text/plain` de ce manifeste rend visible toute application qui accepte un partage de texte, un
 *    traducteur compris, avec toutes ses actions. [ComposeFoundationFlags.isSmartSelectionEnabled] à
 *    `false` retire ces actions (et la sélection intelligente) de toute barre Compose du processus,
 *    sur toutes les versions. Le `<queries>` `PROCESS_TEXT`, qui les rendait toutes visibles, est
 *    retiré du manifeste en plus.
 *
 * Ce qui se perd : la sélection intelligente (un appui long qui sélectionne une adresse entière), les
 * actions du classifieur (appeler, ouvrir une carte, traduire) et la lecture à voix haute dans la
 * barre (elle reste joignable par son propre raccourci d'accessibilité). Couper, Copier, Coller et
 * Tout sélectionner restent : la barre ne garde que l'édition, comme le corps d'une note de Notes Tech.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Activity.keepSelectedTextInApp() {
    getSystemService(TextClassificationManager::class.java)?.setTextClassifier(TextClassifier.NO_OP)
    ComposeFoundationFlags.isSmartSelectionEnabled = false
}
