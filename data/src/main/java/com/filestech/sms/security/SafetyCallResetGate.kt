package com.filestech.sms.security

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * v1.28.4 (F06, suite de la relecture externe de la MR F-Droid !38458) — **la porte que doit
 * franchir un geste « Confirme que tu vas bien » venu d'une notification avant de désarmer.**
 *
 * La v1.28.3 a posé l'attente dans `MainActivity` : le nonce prouve que l'intention vient de
 * notre notification, pas QUI tient le téléphone, et le geste **désarme** l'homme mort. Elle l'a
 * posée en ligne, dans une activité que rien ne teste. La règle vit maintenant ici, seule et
 * testable, et l'activité ne fait que l'appliquer.
 *
 * Ce qu'elle dit : on n'exécute que sur un état **réellement ouvert** — [AppLockManager.LockState.Unlocked],
 * ou [AppLockManager.LockState.Disabled] quand il n'y a pas de verrou. L'attente n'écrit rien : si
 * l'application n'est jamais réellement ouverte, l'homme mort continue de courir — le bon sens de
 * l'échec.
 *
 * v1.28.5 (sixième note d'Andrew, point 5) — **retenir n'est pas la bonne politique pour tous les
 * états fermés.** `Locked` est traversé : c'est le cas légitime, le téléphone est déverrouillé,
 * l'application demande son code, l'utilisateur le saisit et le geste s'exécute. Mais `LockedOut`
 * et `PanicDecoy` disent autre chose — quelqu'un s'est trompé de code plusieurs fois, ou a saisi
 * le code leurre sous contrainte. Un geste retenu là aurait DÉSARMÉ plus tard, au premier vrai
 * déverrouillage de la même session, alors qu'une ouverture réelle ne fait que remettre le minuteur
 * à zéro, jamais désarmer : le geste ajoutait un désarmement que l'utilisateur n'avait pas fait.
 * Le geste venu d'une notification est donc **jeté** sur ces deux états ; celui de la remise à zéro
 * à l'ouverture, qui ne désarme rien, continue d'attendre.
 */
object SafetyCallResetGate {

    fun ouvre(state: AppLockManager.LockState): Boolean =
        state is AppLockManager.LockState.Unlocked || state is AppLockManager.LockState.Disabled

    /** Les états qui jettent un geste de DÉSARMEMENT : trop d'échecs, ou session leurre. */
    fun jette(state: AppLockManager.LockState): Boolean =
        state is AppLockManager.LockState.LockedOut || state is AppLockManager.LockState.PanicDecoy

    /** Suspend jusqu'au premier état qui [ouvre]. Pour la remise à zéro à l'ouverture, qui ne désarme rien. */
    suspend fun attendreOuverture(states: Flow<AppLockManager.LockState>) {
        states.first { ouvre(it) }
    }

    /**
     * Suspend jusqu'au premier état qui [ouvre] — rend `true` — ou qui [jette] — rend `false`,
     * et le geste doit alors être abandonné. Seul `Locked` est traversé.
     */
    suspend fun attendreOuvertureOuJeter(states: Flow<AppLockManager.LockState>): Boolean =
        ouvre(states.first { ouvre(it) || jette(it) })
}
