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
 * ou [AppLockManager.LockState.Disabled] quand il n'y a pas de verrou. `Locked`, `LockedOut` et
 * `PanicDecoy` retiennent le geste ; en particulier la session leurre, qui est précisément ce
 * qu'un agresseur obtient sous contrainte. L'attente n'écrit rien : si l'application n'est jamais
 * réellement ouverte, l'homme mort continue de courir — le bon sens de l'échec.
 */
object SafetyCallResetGate {

    fun ouvre(state: AppLockManager.LockState): Boolean =
        state is AppLockManager.LockState.Unlocked || state is AppLockManager.LockState.Disabled

    /** Suspend jusqu'au premier état qui [ouvre]. */
    suspend fun attendreOuverture(states: Flow<AppLockManager.LockState>) {
        states.first { ouvre(it) }
    }
}
