package com.filestech.sms.security

import com.filestech.sms.core.crypto.PasswordKdf
import com.filestech.sms.core.crypto.wipe
import com.filestech.sms.data.local.datastore.SecurityStore
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.13.0 — second-factor PIN dédié au coffre, complètement séparé du PIN d'app
 * géré par [AppLockManager].
 *
 * **Threat model couvert** :
 *  - "J'ai vu ton PIN d'app par-dessus ton épaule, je vais lire ton coffre" :
 *    le PIN coffre est un hash distinct, le second-factor demande une nouvelle
 *    saisie.
 *  - "Tu m'as confié ton PIN d'app pour que je récupère un SMS, mais le coffre
 *    contient des conv perso" : pareil — l'attaquant connaît le PIN d'app, pas
 *    le PIN coffre.
 *
 * **Hors champ** :
 *  - Forensique avec accès Keystore + clé SQLCipher déballée : tout le contenu
 *    de la base est encore chiffré par la même clé maître que le reste. Le PIN
 *    coffre est un GATE UI / domaine, pas une seconde enveloppe crypto. C'est
 *    documenté dans SECURITY.md (cf. § "What this does **not** give you").
 *  - PIN coffre oublié : il n'y a pas de récupération. L'user peut désactiver
 *    le PIN coffre depuis Réglages → Sécurité s'il connaît encore son PIN d'app
 *    (un user déverrouillé peut toujours toggle OFF). S'il a OUBLIÉ les DEUX,
 *    PanicCode reste l'échappatoire (decoy view) — le coffre ne s'ouvre pas
 *    mais le reste de l'app est utilisable.
 *
 * **Politique crypto** :
 *  - PBKDF2-HMAC-SHA512 avec sel 16B + ≥ 210 000 itérations (calibrate device).
 *  - Stockage dans [SecurityStore] sous le préfixe `vault.*`, indépendant des
 *    clés `pin.*` (app) et `panic.*` (decoy).
 *  - Pas de lockout dédié : le coffre n'est atteignable QU'APRÈS unlock app,
 *    et celui-ci est déjà bornée par le backoff exponentiel d'AppLockManager.
 *    Ajouter un second backoff serait redondant (Single point of slowdown).
 *  - `MessageDigest.isEqual` constant-time pour la comparaison.
 *
 * **Concurrence** : `setVaultPin` / `clearVaultPin` / `verifyVaultPin` sont
 * suspend + `withContext(io)` ; DataStore garantit l'atomicité read-modify-write
 * sur les 3 clés. Pas de mutex applicatif nécessaire.
 */
@Singleton
class VaultPinManager @Inject constructor(
    private val securityStore: SecurityStore,
    private val settings: SettingsRepository,
    private val kdf: PasswordKdf,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Configure (ou remplace) le PIN coffre. [newPin] est wipé à la sortie.
     * Met à jour le flag `vaultPinEnabled=true` dans `SettingsRepository`
     * pour que l'UI (toggle + `VaultScreen` LaunchedEffect) déclenche le gate.
     */
    suspend fun setVaultPin(newPin: CharArray): Unit = withContext(io) {
        val salt = kdf.newSalt()
        val iters = kdf.calibrate()
        try {
            val hash = kdf.derive(newPin, salt, iters)
            securityStore.setVaultPinHash(salt, hash, iters)
            // v1.13.0 audit NEW-5 — pose le flag DANS le try, après le hash.
            // Si `settings.update` lève (IOException DataStore rare), le hash
            // est posé mais le flag reste false → `isVaultPinConfigured()`
            // détecte l'incohérence et le `vaultPinRequired` flow retourne
            // false (gate désactivé). L'user pourra re-setup. Plus safe que
            // l'inverse (flag true sans hash = gate impossible à franchir).
            settings.update { it.copy(security = it.security.copy(vaultPinEnabled = true)) }
        } finally {
            newPin.wipe()
        }
    }

    /**
     * Désactive le PIN coffre : efface le hash stocké + flip le flag. Idempotent
     * (un appel sur flag déjà OFF est un no-op DataStore — l'update transforme
     * le même objet, et DataStore détecte l'absence de delta).
     */
    suspend fun clearVaultPin(): Unit = withContext(io) {
        // v1.13.0 audit NEW-5 — flip le flag AVANT de retirer le hash. Si
        // `securityStore.clearVaultPin()` lève, le flag est déjà false → le
        // `vaultPinRequired` flow retourne false, le hash résiduel est ignoré
        // par `isVaultPinConfigured()` au prochain check (et reste innofensif
        // : pas d'auth tant que le flag n'est pas true).
        settings.update { it.copy(security = it.security.copy(vaultPinEnabled = false)) }
        securityStore.clearVaultPin()
    }

    /**
     * Vérifie [candidate] contre le hash stocké. Retourne `false` si :
     *  - aucun hash configuré (vaultPinEnabled=false avec snapshot=null)
     *  - hash ne matche pas (constant-time)
     *
     * [candidate] est wipé à la sortie. Le caller (dialog UI) DOIT préparer
     * un fresh CharArray à chaque tentative.
     */
    suspend fun verifyVaultPin(candidate: CharArray): Boolean = withContext(io) {
        try {
            val snap = securityStore.vaultPinSnapshot() ?: return@withContext false
            val derived = kdf.derive(candidate, snap.salt, snap.iterations)
            try {
                java.security.MessageDigest.isEqual(derived, snap.hash)
            } finally {
                derived.wipe()
            }
        } finally {
            candidate.wipe()
        }
    }

    /**
     * `true` si un hash est posé en store (état post-setVaultPin). Ne lit
     * PAS le flag settings — c'est l'autorité authoritative côté crypto.
     * Permet à l'UI de détecter une incohérence (flag ON mais hash absent)
     * pour proposer un re-set propre.
     */
    suspend fun isVaultPinConfigured(): Boolean = withContext(io) {
        securityStore.vaultPinSnapshot() != null
    }
}
