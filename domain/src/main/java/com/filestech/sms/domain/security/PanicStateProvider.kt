package com.filestech.sms.domain.security

/**
 * Port domaine : la session courante est-elle un déverrouillage **panic-decoy** ?
 *
 * Les use-cases d'urgence ([com.filestech.sms.domain.usecase.IAmOkUseCase],
 * [com.filestech.sms.domain.usecase.TriggerEmergencyUseCase],
 * [com.filestech.sms.domain.usecase.TriggerSafetyCallUseCase]) refusent d'agir sous une session
 * decoy (un agresseur en decoy ne doit pas pouvoir déclencher / annuler un flux d'urgence). Ils ne
 * lisent qu'une capacité booléenne ponctuelle, pas le flux d'état complet.
 *
 * L'implémentation [com.filestech.sms.security.AppLockManager] expose l'état de verrouillage complet
 * (`StateFlow<LockState>`) à l'UI et à la couche security ; ce port n'en projette que la question
 * decoy (ségrégation d'interface).
 */
interface PanicStateProvider {

    /** `true` si l'application est actuellement déverrouillée en mode panic-decoy. */
    val isPanicDecoyActive: Boolean
}
