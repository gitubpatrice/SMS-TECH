package com.filestech.sms.system.notifications

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.3.9 — Singleton qui mémorise l'identifiant de la conversation actuellement
 * ouverte au premier plan dans le `ThreadScreen`. Permet à
 * [IncomingMessageNotifier] de poser la notification avec un `setTimeoutAfter`
 * court (≈ 1500 ms) quand un message arrive sur la conv que l'utilisateur est
 * en train de regarder — le son + heads-up jouent (signal sonore préservé)
 * mais la notification ne persiste pas dans le shade puisque l'utilisateur
 * voit déjà le message en temps réel.
 *
 * **Pattern attendu** :
 *   - `ThreadViewModel.init` → `setActive(conversationId)`
 *   - `ThreadViewModel.onCleared` → `clearActive(conversationId)`
 *   - `IncomingMessageNotifier.notifyIncomingSms` → check `isActive(conversationId)`
 *     pour décider du `setTimeoutAfter` court vs persistance par défaut.
 *
 * **Thread-safety** : `AtomicLong` garantit lecture/écriture atomiques entre le
 * thread main (où le ViewModel set/clear) et les threads BroadcastReceiver +
 * coroutines IO (où le Notifier read). Aucun lock explicite nécessaire.
 *
 * **Robustesse** :
 *   - `setActive` écrit la valeur sans condition. Si un autre thread vient de
 *     poser une autre conv, on écrase — sémantique "dernière conv ouverte
 *     gagne", correcte car le ViewModel précédent vient nécessairement de
 *     subir un `onCleared` qui aurait clear de toute façon (sauf
 *     race configChange, où l'ordre est : new init avant old onCleared ;
 *     dans ce cas la nouvelle conv reste active, OK).
 *   - `clearActive` ne clear QUE si la valeur courante matche, pour éviter
 *     qu'un `onCleared` tardif d'une conv quittée n'efface l'état d'une conv
 *     fraîchement ouverte (race configChange ci-dessus).
 */
@Singleton
class ActiveConversationTracker @Inject constructor() {

    private val activeId: AtomicLong = AtomicLong(NONE)

    fun setActive(conversationId: Long) {
        activeId.set(conversationId)
    }

    fun clearActive(conversationId: Long) {
        // CAS guard : ne clear que si on est encore propriétaire de l'état, pour
        // éviter qu'un onCleared tardif n'écrase une init survenue entre-temps
        // (cas configChange où le nouveau ViewModel s'init AVANT que l'ancien
        // n'appelle son onCleared).
        activeId.compareAndSet(conversationId, NONE)
    }

    fun isActive(conversationId: Long): Boolean = activeId.get() == conversationId

    private companion object {
        const val NONE = -1L
    }
}
