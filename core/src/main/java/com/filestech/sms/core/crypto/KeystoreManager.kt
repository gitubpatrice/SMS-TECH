package com.filestech.sms.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around the AndroidKeyStore for AES-256-GCM keys.
 *
 * One key per logical purpose:
 *  - "db_master"       : SQLCipher master key
 *  - "vault_kek"       : KEK for the secure vault
 *  - "settings_aead"   : encrypts sensitive DataStore entries
 *  - "panic_decoy"     : panic-mode decoy key (separate from db_master)
 *
 * Keys are non-exportable; AES-GCM nonces are caller-provided 12-byte arrays.
 */
@Singleton
class KeystoreManager @Inject constructor() {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    /**
     * Audit H1 (v1.14.8) — `allowUserIv` (= `setRandomizedEncryptionRequired(false)`) défaut
     * **false** désormais (i.e. Keystore impose la randomisation). Avant : valeur globale `false`
     * sur tous les alias par construction, ce qui désarmait la protection OS sans nécessité
     * pour 3 alias sur 4. Pratique : seul [ALIAS_DB_MASTER] passe explicitement `allowUserIv =
     * true` car [com.filestech.sms.data.local.db.DatabaseKeyManager] s'appuie sur la fresh-IV
     * générée par [com.filestech.sms.core.crypto.AeadCipher]. Les autres aliases conservent le
     * défaut renforcé tant qu'aucun call site documenté ne requiert un user-IV.
     */
    fun getOrCreateKey(
        alias: String,
        userAuthRequired: Boolean = false,
        allowUserIv: Boolean = false,
    ): SecretKey {
        keyStore.getKey(alias, null)?.let { return it as SecretKey }
        return generateKey(alias, userAuthRequired, allowUserIv)
    }

    fun deleteKey(alias: String) {
        runCatching { keyStore.deleteEntry(alias) }
            .onFailure { Timber.w(it, "KeystoreManager: failed to delete %s", alias) }
    }

    fun containsAlias(alias: String): Boolean = keyStore.containsAlias(alias)

    private fun generateKey(alias: String, userAuthRequired: Boolean, allowUserIv: Boolean): SecretKey {
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            // Audit H1 — Protection OS contre les IVs prévisibles ACTIVÉE par défaut.
            // Désactivée uniquement pour les aliases dont l'appelant garantit cryptographiquement
            // la génération d'un IV fresh via SecureRandom (cf. [AeadCipher.encrypt]).
            .setRandomizedEncryptionRequired(!allowUserIv)
            .setUserAuthenticationRequired(userAuthRequired)
            .apply {
                if (!userAuthRequired) return@apply
                // v1.25.3 — disponible depuis l'API 24, donc sur tout le périmètre (minSdk 26) et
                // non plus à partir de l'API 30 seulement. En dessous, on s'en remettait au défaut
                // implicite de la plateforme pour une propriété de SÉCURITÉ : c'est elle qui
                // invalide la clé quand l'utilisateur ré-inscrit une empreinte, et donc elle qui
                // fait basculer proprement sur le PIN au lieu d'ouvrir avec une biométrie changée.
                setInvalidatedByBiometricEnrollment(true)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    // v1.25.3 — sur API 30+, exprimer explicitement la politique d'authentification
                    // plutôt que de s'en remettre au défaut hérité de
                    // `setUserAuthenticationValidityDurationSeconds` : timeout 0 = authentification
                    // exigée à CHAQUE usage de la clé, et `AUTH_BIOMETRIC_STRONG` seul, sans repli
                    // sur le code de verrouillage de l'appareil. C'est ce qui rend le déverrouillage
                    // biométrique cryptographiquement réel : sans présentation biométrique, la clé
                    // ne s'ouvre pas, quel que soit l'état de l'application.
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                }
            }
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_SIZE_BITS = 256

        const val ALIAS_DB_MASTER = "smstech_db_master"
        const val ALIAS_VAULT_KEK = "smstech_vault_kek"
        const val ALIAS_SETTINGS_AEAD = "smstech_settings_aead"
        const val ALIAS_PANIC_DECOY = "smstech_panic_decoy"

        /**
         * v1.25.3 — clé adossée à la biométrie, seule à porter `userAuthRequired = true`.
         *
         * Elle ne chiffre aucune donnée : elle sert de **preuve**. Le déverrouillage biométrique
         * était jusqu'ici un portail d'interface — `BiometricPrompt` réussissait, le ViewModel
         * notait « déverrouillé », et rien dans le système de clés ne l'attestait. Un `doFinal`
         * réussi sur cette clé prouve que l'OS a bien reçu une empreinte Classe 3.
         */
        const val ALIAS_BIOMETRIC_GATE = "smstech_biometric_gate"
    }
}
