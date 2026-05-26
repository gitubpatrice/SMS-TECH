package com.filestech.sms.system.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.8.0 (audit bug 4 — tap notif → écran détail) — étape d'attente entre la
 * réception d'un `Intent` "com.filestech.sms.OPEN_CONVERSATION" (depuis le tap
 * d'une notification de message entrant) et la navigation effective vers
 * [com.filestech.sms.ui.navigation.Thread] dans le `NavController` Compose.
 *
 * **Pourquoi un holder dédié ?** [com.filestech.sms.MainActivity] reçoit l'intent
 * dans `onCreate` ou `onNewIntent` — mais à ce moment, le `NavController`
 * Compose n'est pas encore disponible (il est créé dans la composition par
 * `rememberNavController()`). On dépose le `conversationId` dans ce singleton
 * partagé, et [com.filestech.sms.ui.AppRoot] le consomme via un
 * `LaunchedEffect` une fois le `NavController` instancié + après les guards
 * (lock screen / panic-decoy / déjà sur ce thread).
 *
 * Pattern strictement aligné sur [com.filestech.sms.system.share
 * .IncomingShareHolder] (v1.3.3) pour préserver la cohérence du codebase :
 * mêmes invariants (singleton process-wide, in-memory, TTL court protégeant
 * d'un holder oublié), même API `set` / `consume` / `clear`.
 *
 * **Sécurité** :
 *  - Aucune coercition de visibilité — un `conversationId` est public (juste
 *    un Long Room interne). Pas de body, pas d'address ici : le tap suffit à
 *    désigner la conversation cible.
 *  - TTL [PENDING_TTL_MS] = 30 s : couvre un déverrouillage utilisateur normal
 *    (unlock biométrique + transition AppRoot) sans risque d'attacher un
 *    `conversationId` oublié à une session d'app ouverte plus tard.
 *  - Le `LaunchedEffect` côté AppRoot revalide les guards à chaque tick (lock
 *    actif → return ; panic-decoy → return) — un tap notif posé pendant
 *    qu'on était dans un état autorisé puis devenu interdit (panic activé
 *    entre-temps) ne se déclenche jamais.
 */
@Singleton
class PendingNavHolder @Inject constructor() {

    /**
     * Représente une navigation en attente : le `conversationId` cible cliqué
     * dans une notification + timestamp epoch ms de la pose, utilisé pour
     * [isExpired].
     */
    data class Pending(
        val conversationId: Long = -1L,
        /**
         * v1.14.1 — `true` si la cible de navigation est l'écran Mode urgence
         * (tap sur le corps de la notification persistante lock-screen). Au
         * moins un de `conversationId > 0` ou `openEmergency = true` doit être
         * vrai pour que le pending soit considéré valide ([isValid]).
         */
        val openEmergency: Boolean = false,
        /**
         * v1.14.8 (bug "Message" depuis Phone app) — adresse téléphone reçue via
         * `Intent.ACTION_SENDTO` / `ACTION_VIEW` + scheme `sms:`/`smsto:`/`mms:`/`mmsto:`.
         * AppRoot résout l'adresse via [com.filestech.sms.domain.repository.ConversationRepository.findOrCreate]
         * (conv existante → ouverte directement ; sinon créée puis ouverte) et navigue vers
         * [com.filestech.sms.ui.navigation.Thread]. Le body optionnel est staged dans
         * [com.filestech.sms.system.share.IncomingShareHolder] pour pré-remplir le composer.
         *
         * Sécurité : valide phone number côté caller (MainActivity) — pas de chemin de
         * confiance entre une app tierce et un thread arbitraire. Non-PII en log.
         */
        val sendToAddress: String? = null,
        val postedAt: Long = System.currentTimeMillis(),
    ) {
        fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
            now - postedAt > PENDING_TTL_MS

        /** v1.14.1 — accepte conv tap (id valid) OU emergency tap OU sms: deep-link. */
        val isValid: Boolean
            get() = conversationId > 0L || openEmergency || !sendToAddress.isNullOrBlank()
    }

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    /**
     * Pose une navigation en attente. Écrase silencieusement la précédente —
     * comportement souhaité : si deux notifs sont tapées rapidement, seule la
     * dernière compte (celle dont l'utilisateur veut voir le thread).
     * Refuse silencieusement un pending invalide (ni conv ni emergency).
     */
    fun set(pending: Pending) {
        if (!pending.isValid) return
        _pending.value = pending
    }

    /**
     * Consomme et efface la navigation en attente. Idempotent. Retourne `null`
     * ET clear si le pending est expiré ([PENDING_TTL_MS]) — même pattern que
     * [com.filestech.sms.system.share.IncomingShareHolder.consume].
     */
    fun consume(): Pending? {
        val current = _pending.value
        _pending.value = null
        if (current != null && current.isExpired()) return null
        return current
    }

    /** Efface sans lire (cas où l'utilisateur annule ou intent non-OPEN_CONVERSATION). */
    fun clear() {
        _pending.value = null
    }

    companion object {
        /**
         * TTL d'une navigation en attente. 30 s : couvre un déverrouillage
         * biométrique (≈3 s) + transition Compose AppRoot (<1 s) avec marge
         * confortable, sans risque qu'un tap oublié dans une session
         * précédente ne pousse aveuglément vers un thread inattendu.
         */
        const val PENDING_TTL_MS: Long = 30_000L
    }
}
