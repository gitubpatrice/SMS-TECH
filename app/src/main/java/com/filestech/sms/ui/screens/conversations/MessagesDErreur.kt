package com.filestech.sms.ui.screens.conversations

/**
 * v1.28.9 — le texte des événements [ConversationsViewModel.Event.Erreur].
 *
 * Les chaînes sont résolues par `stringResource` à la composition, comme l'audit H16 l'a posé pour
 * l'échec de blocage : `ctx.getString` dans le collecteur rate les changements de configuration.
 * Le CHOIX du texte vit ici, hors de [ConversationsScreen], dont la complexité est au seuil de
 * detekt : l'écran n'a qu'une branche pour toutes les erreurs.
 */
internal class MessagesDErreur(
    private val blocageEchoue: String,
    private val suppressionCopieSysteme: String,
    private val suppressionLocale: String,
) {
    fun pour(erreur: ConversationsViewModel.Event.Erreur): String = when (erreur) {
        ConversationsViewModel.Event.BlockFailed -> blocageEchoue
        is ConversationsViewModel.Event.DeleteKept ->
            if (erreur.copieSysteme) suppressionCopieSysteme else suppressionLocale
    }
}
