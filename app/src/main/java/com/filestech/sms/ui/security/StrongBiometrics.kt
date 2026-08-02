package com.filestech.sms.ui.security

import android.content.Context
import androidx.biometric.BiometricManager

/**
 * Politique biométrique unique de l'app — v1.25.3 (audit H2).
 *
 * Avant, chaque écran redemandait `BIOMETRIC_WEAK` dans son coin (une fois dans `LockScreen`,
 * trois fois dans `VaultScreen`). `BIOMETRIC_WEAK` accepte la Classe 2, c'est-à-dire la
 * reconnaissance faciale 2D d'un simple capteur photo : une photo du visage suffit à ouvrir
 * l'app **et le coffre**. On exige désormais la Classe 3 (`BIOMETRIC_STRONG`), et une seule
 * fois, ici : ne jamais rappeler `canAuthenticate` ailleurs, sinon la politique redivergera.
 *
 * Aucun risque d'enfermement : `AppLockManager.enableBiometric()` refuse d'armer le mode
 * biométrique tant qu'aucun PIN n'existe, donc un appareil sans capteur Classe 3 retombe
 * toujours sur une saisie PIN utilisable.
 */
object StrongBiometrics {

    /** Classe 3 uniquement. Le seul endroit de l'app où ce choix est fait. */
    val AUTHENTICATORS: Int = BiometricManager.Authenticators.BIOMETRIC_STRONG

    /** Code `BiometricManager.BIOMETRIC_*` brut, pour distinguer indispo passagère et définitive. */
    fun status(context: Context): Int =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)

    fun isAvailable(context: Context): Boolean =
        status(context) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Vrai quand l'indisponibilité ne se résoudra pas d'elle-même : pas de capteur Classe 3, ou
     * aucune empreinte enrôlée. Distinguer compte — sur une indispo **passagère**
     * (`HW_UNAVAILABLE`, capteur occupé), désarmer le réglage ferait perdre silencieusement le
     * déverrouillage biométrique après un simple raté.
     */
    fun isPermanentlyUnavailable(status: Int): Boolean =
        status == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ||
            status == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
}
