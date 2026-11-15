package com.filestech.sms.system.notifications

import java.util.IdentityHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.3.9 — Singleton qui mémorise la conversation actuellement ouverte au premier plan dans le
 * `ThreadScreen`. Permet à [IncomingMessageNotifier] de poser la notification avec un
 * `setTimeoutAfter` court (≈ 1500 ms) quand un message arrive sur la conv que l'utilisateur est en
 * train de regarder — le son + heads-up jouent (signal sonore préservé) mais la notification ne
 * persiste pas dans le shade puisque l'utilisateur voit déjà le message en temps réel.
 *
 * **Pattern attendu** :
 *   - `ThreadScreen`, ON_START → `ThreadViewModel.onVisibilityChanged(true)` → `setActive(conversationId, vm)`
 *   - ON_STOP, puis `ThreadViewModel.onCleared` en filet → `clearActive(vm)`. Jusqu'à la
 *     1.29.0, `init` et `onCleared` seuls : le ViewModel survivant à l'écran éteint, la conversation
 *     restait « active » sans être vue (issue #60).
 *   - `IncomingMessageNotifier.notifyIncoming` → check `isActive(conversationId)`
 *     pour décider du `setTimeoutAfter` court vs persistance par défaut.
 *
 * **2026-11-15 (v1.30.1, issue #60, second signalement) — l'ensemble des écrans DÉMARRÉS, et non plus
 * une seule valeur.** Le tracker gardait un identifiant, effacé par `clearActive(conversationId)`. Or deux
 * écrans peuvent être démarrés en même temps, et le premier à s'arrêter n'est pas toujours le dernier
 * démarré :
 *   - une transition de navigation démarre l'écran entrant avant d'arrêter le sortant : deux
 *     `Thread(A)` (un lien `sms:` vers la conversation déjà ouverte, puis Retour) faisaient effacer par
 *     le sortant l'état de l'entrant — mesuré sur émulateur, la notification restait ;
 *   - un retour prédictif ANNULÉ démarre puis arrête l'écran du dessous, sans redémarrer celui du
 *     dessus ; un retour pendant la transition arrête l'entrant, sans redémarrer le sortant. Avec une
 *     seule valeur, même attribuée à son propriétaire, l'écran resté visible perdait son état pour de
 *     bon (relecture Claude du 2026-11-15).
 * Chaque écran démarré est donc inscrit au nom de son ViewModel, et désinscrit par lui seul : une
 * conversation est « à l'écran » tant qu'au moins un écran démarré la montre.
 *
 * **Thread-safety** : le thread principal inscrit et désinscrit, le receveur de SMS et les
 * coroutines d'E/S lisent ; un verrou sur la table suffit, les sections étant de quelques
 * instructions. **Fuite** : aucune — un ViewModel est désinscrit à son ON_STOP, et au plus tard dans
 * son `onCleared`.
 */
@Singleton
class ActiveConversationTracker @Inject constructor() {

    /** Écran démarré (son ViewModel, comparé par IDENTITÉ) → conversation qu'il montre. */
    private val demarres = IdentityHashMap<Any, Long>()

    fun setActive(conversationId: Long, owner: Any) {
        synchronized(demarres) { demarres[owner] = conversationId }
    }

    fun clearActive(owner: Any) {
        synchronized(demarres) { demarres.remove(owner) }
    }

    // ⚠️ Pas `containsValue` : sur une `IdentityHashMap`, il compare aussi les VALEURS par identité, et
    // deux `Long` en boîte du même identifiant ne sont le même objet qu'entre -128 et 127.
    fun isActive(conversationId: Long): Boolean =
        synchronized(demarres) { demarres.values.any { it == conversationId } }
}
