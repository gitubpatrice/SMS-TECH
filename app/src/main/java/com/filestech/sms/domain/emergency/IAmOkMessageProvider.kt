package com.filestech.sms.domain.emergency

/**
 * Port domaine : fournit le corps du SMS « Je vais bien » (fausse alerte) envoyé aux contacts
 * SafetyCall lors d'un reset d'urgence.
 *
 * [com.filestech.sms.domain.usecase.IAmOkUseCase] en dépend au lieu de résoudre lui-même la
 * ressource `R.string` : le use-case reste sans `Context` ni référence au `R` généré de l'app,
 * de sorte que `domain/` ne dépend pas du module `:app`. L'implémentation
 * [com.filestech.sms.data.emergency.IAmOkMessageProviderImpl] lit la chaîne localisée.
 */
interface IAmOkMessageProvider {

    /** Corps localisé du SMS « Je vais bien », tel que stocké dans les ressources. */
    fun body(): String
}
