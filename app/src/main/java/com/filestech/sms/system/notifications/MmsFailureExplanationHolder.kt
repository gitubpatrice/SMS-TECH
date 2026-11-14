package com.filestech.sms.system.notifications

import android.app.PendingIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 2026-11-06 — **la fenêtre à ouvrir après un tap sur « MMS non reçu »**.
 *
 * Signalé par une utilisatrice (Pixel 8, LineageOS) : la notification disait « Tap to open MMS
 * settings », le tap ouvrait la liste des conversations, et l'application n'a aucun réglage MMS. Elle
 * a cherché, n'a rien trouvé, et a écrit. Le tap ouvre désormais une fenêtre qui dit ce qui s'est
 * passé et ce qu'on peut faire ([com.filestech.sms.ui.components.MmsFailureExplanationOverlay]).
 *
 * Même contrat que [SafetyCallAckHolder] et [PendingNavHolder] : `MainActivity` reçoit l'intent avant
 * que la composition n'existe, et la fenêtre attend que l'application soit réellement ouverte — le
 * code saisi, jamais en session leurre. Singleton de process, en mémoire : `set` / `consume` / `clear`.
 *
 * Il porte un [MmsFailureNotifier.Reason], jamais un texte : la phrase est résolue côté composition,
 * dans la langue du moment. Ni expéditeur ni taille : la fenêtre n'a rien à cacher. 2026-11-06 — et, pour
 * un téléchargement manqué, l'[MmsFailureNotifier.ErreurAndroid] validée : deux entiers bornés.
 */
@Singleton
class MmsFailureExplanationHolder @Inject constructor() {

    /**
     * @property affichee la fenêtre a paru à l'écran : elle ne périme plus, et reste jusqu'à sa fermeture —
     *   même après une rotation ou un retour par le verrou (relecture Claude et Gemini du 2026-11-06). Gardé
     *   ICI, dans la seule source, et non dans un `rememberSaveable` qui en ferait une seconde.
     * @property erreur 2026-11-06 — ce qu'Android a dit du téléchargement manqué, ou `null`.
     * @property relance 2026-11-14 — le bouton « Réessayer » du message, déjà validé
     *   ([MmsFailureNotifier.relanceDepuisExtras]), ou `null`.
     */
    data class Pending(
        val reason: MmsFailureNotifier.Reason,
        val postedAt: Long = System.currentTimeMillis(),
        val affichee: Boolean = false,
        val erreur: MmsFailureNotifier.ErreurAndroid? = null,
        val relance: PendingIntent? = null,
    ) {
        fun isExpired(now: Long = System.currentTimeMillis()): Boolean = !affichee && now - postedAt > PENDING_TTL_MS
    }

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    /**
     * Écrase la précédente : le dernier tap décide, erreur et bouton compris — un tap sans code n'hérite pas de
     * celui du tap d'avant, ni du bouton d'un autre message. [nowMs] est injectable, comme chez les jumeaux.
     */
    fun set(
        reason: MmsFailureNotifier.Reason,
        nowMs: Long = System.currentTimeMillis(),
        erreur: MmsFailureNotifier.ErreurAndroid? = null,
        relance: PendingIntent? = null,
    ) {
        _pending.value = Pending(reason, postedAt = nowMs, erreur = erreur, relance = relance)
    }

    /** La fenêtre a paru : elle ne périme plus. Sans effet sur un porteur vide ou déjà marqué. */
    fun marquerAffichee() {
        _pending.update { courant ->
            if (courant == null || courant.affichee) courant else courant.copy(affichee = true)
        }
    }

    /** Consomme et efface ; rend `null`, et efface, si le délai est dépassé. */
    fun consume(nowMs: Long = System.currentTimeMillis()): Pending? {
        val current = _pending.value
        _pending.value = null
        if (current != null && current.isExpired(nowMs)) return null
        return current
    }

    /** Efface sans lire : session leurre, ou fenêtre périmée. */
    fun clear() {
        _pending.value = null
    }

    companion object {
        /**
         * 10 minutes, entre le tap et l'apparition. La fenêtre explique un fait passé : l'afficher tard reste
         * vrai, ne pas l'afficher ramène au silence que ce porteur corrige. Le délai couvre donc un
         * déverrouillage lent. Il existe seulement pour qu'un tap resté sans suite — application jamais
         * déverrouillée — ne fasse pas surgir la fenêtre des heures plus tard, sans lien visible avec rien.
         */
        const val PENDING_TTL_MS: Long = 600_000L
    }
}
