package com.filestech.sms.system.share

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.3.3 bug #4 — étape d'attente entre la réception d'un `ACTION_SEND` (depuis la
 * Galerie, le navigateur, etc. via le chooser Android) et la sélection par l'utilisateur
 * d'une conversation cible. [MainActivity] parse l'intent et `set()` ; [AppRoot] observe
 * et bascule l'UI en mode "picker", l'écran cible (composer / thread) `consume()` une
 * fois la PJ attachée.
 *
 * Singleton process-wide — la donnée est in-memory ; un kill du process annule le
 * partage en cours (acceptable : c'est éphémère et l'user peut relancer depuis la
 * Galerie).
 *
 * Pas de coupling avec Compose / Hilt-ViewModel : un simple Singleton injectable
 * partout.
 */
@Singleton
class IncomingShareHolder @Inject constructor() {

    /**
     * Représente un partage en attente : une ou plusieurs URIs + un type MIME indicatif
     * (le 1ᵉʳ trouvé dans le ClipData) + un texte optionnel (ACTION_SEND text/plain).
     *
     * v1.3.3 G2 audit fix — [postedAt] timestamp epoch ms du `set()`, utilisé par
     * `consume()` pour ignorer un holder expiré (TTL [PENDING_TTL_MS]). Protège contre :
     * (a) user qui partage depuis Galerie puis met l'app en arrière-plan et oublie ;
     * (b) tap sur une notif `OPEN_CONVERSATION` qui consommerait le holder oublié sur
     * la mauvaise conversation.
     */
    data class Pending(
        val uris: List<Uri>,
        val mimeType: String?,
        val text: String?,
        val postedAt: Long = System.currentTimeMillis(),
    ) {
        val isEmpty: Boolean get() = uris.isEmpty() && text.isNullOrBlank()
        fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
            now - postedAt > PENDING_TTL_MS
    }

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    /** Pose un partage en attente. Écrase silencieusement le précédent (rare). */
    fun set(pending: Pending) {
        if (pending.isEmpty) return
        _pending.value = pending
    }

    /**
     * Consomme et efface le partage en attente. Idempotent.
     *
     * v1.3.3 G2 — retourne `null` ET clear si le pending est expiré ([PENDING_TTL_MS]).
     * Protège contre l'attachement involontaire à la mauvaise conversation après un
     * abandon utilisateur.
     */
    fun consume(): Pending? {
        val current = _pending.value
        _pending.value = null
        if (current != null && current.isExpired()) return null
        return current
    }

    /** Efface sans lire (cas où l'utilisateur annule, ou intent non-SEND reçu). */
    fun clear() {
        _pending.value = null
    }

    companion object {
        /**
         * TTL d'un partage en attente. 60 s : couvre une hésitation utilisateur normale
         * (chooser → lock écran → unlock → tap conv) sans risque qu'un holder oublié
         * la veille ne se colle à une conv ouverte aujourd'hui.
         */
        const val PENDING_TTL_MS: Long = 60_000L
    }
}
