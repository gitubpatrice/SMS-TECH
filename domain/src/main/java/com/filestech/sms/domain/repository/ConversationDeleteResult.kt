package com.filestech.sms.domain.repository

/**
 * v1.28.9 (septième note d'Andrew, MR !38458, point 5) — ce qu'il est advenu d'une conversation
 * dont l'utilisateur a demandé la suppression.
 *
 * Hors coffre, la suppression ordinaire efface la ligne locale quoi qu'il arrive : qui efface un
 * fil l'a demandé, et une copie restée dans la messagerie du téléphone y reste visible comme avant.
 * Ce contrat ne vaut pas pour le coffre. Effacer la ligne locale d'une conversation du coffre dont la
 * copie système résiste fait disparaître `in_vault`, le seul drapeau qui la protège : la
 * synchronisation suivante la réimporte HORS du coffre, en clair. Elle est donc conservée, et
 * l'écran le dit.
 */
enum class ConversationDeleteResult {
    /** La conversation n'est plus dans l'application. */
    DELETED,

    /** Conversation du coffre conservée : sa copie dans la messagerie du téléphone résiste. */
    KEPT_SYSTEM_COPY,

    /** Conservée : un élément local n'a pas pu être supprimé, ou l'état de la conversation n'a pas pu être lu. */
    KEPT_LOCAL_FAILURE,
}
