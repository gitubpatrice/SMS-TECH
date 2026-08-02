package com.filestech.sms.ui.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import com.filestech.sms.core.crypto.KeystoreManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.25.3 — adosse le déverrouillage biométrique à une clé du Keystore.
 *
 * Avant, la biométrie était un **portail d'interface** : `BiometricPrompt` réussissait, le
 * ViewModel notait « déverrouillé », et aucune clé n'en attestait. Qui savait manipuler l'état de
 * l'application franchissait le portail sans jamais présenter d'empreinte. Le durcissement du
 * lot 1 a resserré le *type* d'authentification accepté (Classe 3, Coffre compris) — pas ce
 * point-là.
 *
 * Désormais, le prompt reçoit un `CryptoObject` portant un [Cipher] initialisé sur
 * [KeystoreManager.ALIAS_BIOMETRIC_GATE], clé créée avec `setUserAuthenticationRequired(true)`.
 * L'OS refuse d'ouvrir cette clé tant qu'aucune empreinte Classe 3 n'a été présentée : un
 * [confirm] réussi est donc une **preuve**, pas une déclaration de l'application.
 *
 * La clé ne protège aucune donnée. C'est délibéré : elle atteste, elle ne chiffre pas. Y adosser
 * la clé de base ferait perdre tout le contenu à la moindre ré-inscription biométrique.
 */
@Singleton
class BiometricGate @Inject constructor(
    private val keystore: KeystoreManager,
    @com.filestech.sms.di.IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Résultat de la préparation du [Cipher] remis à `BiometricPrompt`. */
    sealed interface Prepared {
        /** Clé disponible : à passer en `CryptoObject`. */
        data class Ready(val cipher: Cipher) : Prepared

        /**
         * La biométrie enrôlée a changé depuis la création de la clé
         * (`setInvalidatedByBiometricEnrollment`). L'appelant DOIT désarmer le réglage : la clé
         * ne s'ouvrira plus jamais, la laisser armée enfermerait l'utilisateur dehors.
         */
        data object Invalidated : Prepared

        /** Keystore indisponible (panne matérielle, OEM capricieux). Repli sur le PIN. */
        data object Unavailable : Prepared
    }

    /**
     * Crée si besoin la clé et rend un [Cipher] prêt à être scellé dans un `CryptoObject`.
     *
     * Toujours hors du thread principal : la génération d'une clé Keystore touche le matériel
     * sécurisé et peut prendre plusieurs centaines de millisecondes sur les appareils d'entrée
     * de gamme.
     */
    suspend fun prepare(): Prepared = withContext(io) {
        try {
            val key = keystore.getOrCreateKey(
                alias = KeystoreManager.ALIAS_BIOMETRIC_GATE,
                userAuthRequired = true,
            )
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            Prepared.Ready(cipher)
        } catch (invalidated: KeyPermanentlyInvalidatedException) {
            // Ré-inscription d'une empreinte, ou verrou d'appareil retiré puis remis. On efface
            // l'alias pour qu'une future ré-activation reparte d'une clé saine.
            Timber.i(invalidated, "BiometricGate: clé invalidée par ré-inscription biométrique")
            keystore.deleteKey(KeystoreManager.ALIAS_BIOMETRIC_GATE)
            Prepared.Invalidated
        } catch (t: Throwable) {
            Timber.w(t, "BiometricGate: préparation impossible")
            Prepared.Unavailable
        }
    }

    /**
     * Consomme le [Cipher] rendu par `BiometricPrompt` après succès.
     *
     * C'est **ici** que se joue la preuve : l'OS n'autorise `doFinal` que si la clé a réellement
     * été déverrouillée par l'authentification qui vient d'avoir lieu. Un `CryptoObject` nul ou
     * un `doFinal` qui échoue signifient que le succès annoncé n'est pas adossé à la clé — on
     * refuse alors le déverrouillage.
     */
    fun confirm(cipher: Cipher?): Boolean {
        if (cipher == null) {
            Timber.w("BiometricGate: succès biométrique sans CryptoObject — refusé")
            return false
        }
        return runCatching { cipher.doFinal(PROOF) != null }
            .onFailure { Timber.w(it, "BiometricGate: doFinal refusé par le Keystore") }
            .getOrDefault(false)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** Bloc arbitraire : seul compte le fait que le Keystore accepte de le traiter. */
        val PROOF = "sms-tech-biometric-gate".toByteArray()
    }
}
