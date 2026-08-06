package com.filestech.sms.system.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.27.4 — **ce que l'application doit confirmer après un geste Safety call venu d'une
 * notification**.
 *
 * # Le défaut fermé ici : le jumeau asymétrique, sur un couple DÉJÀ recousu une fois
 *
 * Constaté par Patrice le 2026-08-06 : « si je tape sur la notification safety call elle m'envoie
 * sur la liste des sms ». L'acquittement **fonctionnait** — cycle archivé, notification retirée —
 * mais l'application ne disait **rien**. Rien ne distinguait « le geste a été pris en compte » de
 * « l'application s'est ouverte sur la liste, comme si j'avais raté la notification ».
 *
 * La chaîne `settings_safety_call_im_ok_stops_relances` — « Safety call désactivé. Plus aucun
 * message ne partira. » — n'était référencée qu'à **un seul** endroit,
 * `SettingsScreen.kt`, c'est-à-dire au bouton des Réglages. Le tap de la notification exécutait la
 * **même** action (`withActivityReset(disarmIfTriggered = true)`) et se taisait.
 *
 * 🔴 Or la v1.27.2 avait déjà traité ce couple, sur le constat Codex SC-03, en alignant les deux
 * gestes **en comportement** — avec ce commentaire dans `MainActivity` : « deux boutons portant la
 * même promesse et agissant différemment, c'est le jumeau asymétrique ». Le comportement a été
 * aligné, **le retour utilisateur ne l'a pas été.**
 *
 * ⇒ **Quand on ferme un jumeau asymétrique, il faut vérifier les deux faces : ce que le code fait,
 * et ce que l'utilisateur voit.** Un geste silencieux et un geste confirmé ne sont pas le même
 * geste, même quand ils écrivent la même chose.
 *
 * ⚠️ Aggravant, et c'est ce qui rend l'absence de retour nuisible plutôt que seulement frustrante :
 * [SafetyCallIntentToken.consume] est **mono-usage**. Sans confirmation, un doute pousse à retaper —
 * et le second tap est rejeté **en silence**. L'absence de retour fabriquait donc le geste qu'elle
 * rendait impossible.
 *
 * # Pourquoi un porteur, et pas un simple `Toast`
 *
 * `MainActivity` reçoit l'intent dans `onCreate` / `onNewIntent`, avant que la composition n'existe,
 * et l'écriture est **asynchrone** : la confirmation ne peut pas être émise là où le geste est reçu.
 * Pattern strictement aligné sur [PendingNavHolder] et
 * [com.filestech.sms.system.share.IncomingShareHolder] — singleton de process, en mémoire,
 * `set` / `consume` / `clear`, TTL.
 *
 * # Un identifiant, jamais une chaîne déjà résolue
 *
 * [Ack] porte un **cas**, pas un texte. Résoudre la chaîne dans `MainActivity` la figerait dans la
 * langue en vigueur à cet instant et déclencherait `LocalContextGetResourceValueCall` côté
 * composition — le même raisonnement que celui déjà écrit dans `SettingsScreen` pour hisser ses
 * `stringResource` hors des lambdas.
 */
@Singleton
class SafetyCallAckHolder @Inject constructor() {

    /** Le geste à confirmer. Un cas par phrase distincte, résolue côté composition. */
    enum class Ack {
        /** « Je vais bien » avant tout déclenchement : le minuteur repart. */
        TIMER_RESET,

        /** Acquittement d'une alerte déjà partie : la protection est coupée. */
        DISARMED,

        /** Bouton « Réactiver » du reçu : la protection est de nouveau en marche. */
        REARMED,
    }

    data class Pending(
        val ack: Ack,
        val postedAt: Long = System.currentTimeMillis(),
    ) {
        fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
            now - postedAt > PENDING_TTL_MS
    }

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    /**
     * Pose une confirmation en attente. Écrase la précédente : deux gestes rapprochés doivent
     * afficher le **dernier** état atteint, jamais l'avant-dernier.
     *
     * ⚠️ [nowMs] est injectable pour la même raison que dans [consume], et l'omettre a coûté
     * quelque chose : une première version de `SafetyCallAckHolderTest` horodatait par l'horloge
     * réelle tout en interrogeant `consume` à un instant fixe **antérieur**. Le délai calculé était
     * alors négatif, donc jamais dépassé, et quatre tests passaient pour cette raison-là et non
     * pour celle qu'ils annonçaient. Une horloge à moitié injectable ne rend pas un porteur à TTL
     * testable — elle rend ses tests silencieusement vacants.
     */
    fun set(ack: Ack, nowMs: Long = System.currentTimeMillis()) {
        _pending.value = Pending(ack, postedAt = nowMs)
    }

    /**
     * Consomme et efface. Rend `null` **et** efface si le délai est dépassé, comme les jumeaux.
     *
     * [nowMs] est injectable pour la même raison que dans
     * [com.filestech.sms.domain.safetycall.SafetyCallConfig.withActivityReset] : sans cela le chemin
     * d'expiration ne serait pas testable, et un chemin non testé sur un porteur à TTL est
     * précisément là où un message se perd sans bruit.
     */
    fun consume(nowMs: Long = System.currentTimeMillis()): Pending? {
        val current = _pending.value
        _pending.value = null
        if (current != null && current.isExpired(nowMs)) return null
        return current
    }

    /** Efface sans lire — utilisé quand la session ne doit rien afficher (mode leurre). */
    fun clear() {
        _pending.value = null
    }

    companion object {
        /**
         * 2 minutes, et **volontairement plus généreux** que les 30 s de [PendingNavHolder].
         *
         * Le raisonnement est celui du sens dans lequel le repli échoue, pas celui du confort. Une
         * navigation en attente désigne une cible : périmée, elle pousserait vers un fil inattendu,
         * donc un TTL court est une garde. Une confirmation, elle, énonce un **fait déjà acquis** :
         * l'écriture a réussi avant que le porteur ne soit rempli. L'afficher tard reste **vrai** ;
         * ne pas l'afficher ramène au défaut que ce fichier corrige.
         *
         * Le délai couvre donc un déverrouillage lent — reconnaissance biométrique échouée puis
         * code saisi à la main, écran verrouillé sur un téléphone qu'on va chercher — au lieu de
         * l'exclure.
         */
        const val PENDING_TTL_MS: Long = 120_000L
    }
}
