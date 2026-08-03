package com.filestech.sms.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.26.1 (audit H1) — porteur de l'état « le Coffre est ouvert pour cette session ».
 *
 * **Pourquoi une classe séparée plutôt qu'un champ de [VaultManager].**
 *
 * Deux raisons, et la première est bloquante :
 *
 *  1. **Cycle de dépendances.** [VaultManager] dépend de `ConversationRepository`. La couche
 *     données doit désormais consulter l'état du Coffre pour masquer son contenu tant que le
 *     second facteur n'a pas été franchi — injecter [VaultManager] dans
 *     `ConversationRepositoryImpl` fermerait donc le cycle et casserait le graphe Hilt.
 *     Ce porteur n'a AUCUNE dépendance : les deux côtés peuvent en dépendre sans se croiser.
 *
 *  2. **Réactivité.** L'état vivait dans un `AtomicBoolean`, qui n'est pas observable. Un flux
 *     Compose qui l'aurait simplement lu n'aurait jamais été ré-évalué au moment où
 *     l'utilisateur ouvre son Coffre : le fil serait resté vide jusqu'à ce qu'une autre source
 *     émette. Un [StateFlow] rend la transition observable.
 *
 * La sémantique de l'`AtomicBoolean` d'origine est préservée : lecture et écriture de
 * `MutableStateFlow.value` sont atomiques et visibles entre threads (le flag est partagé entre
 * les coroutines IO et l'UI).
 *
 * ⚠️ L'état est **volontairement en mémoire seulement** : il ne survit pas à la mort du
 * processus. Un Coffre déverrouillé ne doit jamais le rester après un redémarrage.
 */
@Singleton
class VaultSessionState @Inject constructor() {

    private val _unlocked = MutableStateFlow(false)

    /** Observable — à préférer partout où une UI ou un flux doit réagir au déverrouillage. */
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    /** Lecture ponctuelle, pour les appels non réactifs (gardes d'action). */
    val isUnlocked: Boolean get() = _unlocked.value

    /** L'appelant DOIT déjà avoir authentifié l'utilisateur (PIN coffre et/ou biométrie). */
    fun markUnlocked() { _unlocked.value = true }

    fun lock() { _unlocked.value = false }
}
