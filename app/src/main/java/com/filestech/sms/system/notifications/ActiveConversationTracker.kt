package com.filestech.sms.system.notifications

import java.util.concurrent.atomic.AtomicReference
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
 *   - `ThreadScreen`, ON_START → `ThreadViewModel.onVisibilityChanged(true)` → `setActive(conversationId, vm)`
 *   - ON_STOP, puis `ThreadViewModel.onCleared` en filet → `clearActive(vm)`. Jusqu'à la
 *     1.29.0, `init` et `onCleared` seuls : le ViewModel survivant à l'écran éteint, la conversation
 *     restait « active » sans être vue (issue #60).
 *   - `IncomingMessageNotifier.notifyIncoming` → check `isActive(conversationId)`
 *     pour décider du `setTimeoutAfter` court vs persistance par défaut.
 *
 * **2026-11-15 (v1.30.1, issue #60, second signalement) — l'état appartient à un ÉCRAN, plus à un
 * identifiant.** Le `clearActive(conversationId)` d'avant ne s'effaçait « que si la valeur courante
 * matche » : il protégeait une conversation d'une AUTRE, jamais deux écrans de la MÊME. Or une
 * transition de navigation démarre l'écran entrant (ON_START) avant d'arrêter le sortant (ON_STOP) :
 * deux `Thread(A)` qui se succèdent (un lien `sms:` vers la conversation déjà ouverte, le retour de
 * l'un vers l'autre) faisaient effacer par le sortant l'état que l'entrant venait de poser. La
 * conversation restait sous les yeux, mais ses notifications ne s'effaçaient plus. Le propriétaire
 * ([setActive]'s `owner`, le ViewModel de l'écran) tranche : seul celui qui a posé l'état l'efface.
 *
 * **Thread-safety** : `AtomicReference` garantit lecture/écriture atomiques entre le
 * thread main (où le ViewModel set/clear) et les threads BroadcastReceiver +
 * coroutines IO (où le Notifier read). Aucun lock explicite nécessaire.
 *
 * **Robustesse** :
 *   - `setActive` écrit la valeur sans condition : « le dernier écran visible gagne ».
 *   - `clearActive` n'efface QUE si l'état appartient encore à [owner] (comparaison d'IDENTITÉ,
 *     `compareAndSet`) : un arrêt tardif — écran sortant d'une transition, `onCleared` après un
 *     changement de configuration — ne peut plus effacer l'état d'un écran entré entre-temps, que ce
 *     soit pour une autre conversation ou pour la même.
 */
@Singleton
class ActiveConversationTracker @Inject constructor() {

    /** L'écran visible et sa conversation ; `null` quand aucune conversation n'est à l'écran. */
    private class Actif(val conversationId: Long, val owner: Any)

    private val actif = AtomicReference<Actif?>(null)

    fun setActive(conversationId: Long, owner: Any) {
        actif.set(Actif(conversationId, owner))
    }

    fun clearActive(owner: Any) {
        // Boucle de `compareAndSet` sur l'objet LU : l'état n'est effacé que s'il n'a pas changé
        // entre la lecture et l'écriture, et seulement s'il appartient à [owner].
        while (true) {
            val courant = actif.get() ?: return
            if (courant.owner !== owner) return
            if (actif.compareAndSet(courant, null)) return
        }
    }

    fun isActive(conversationId: Long): Boolean = actif.get()?.conversationId == conversationId
}
