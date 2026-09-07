package com.filestech.sms.security

import android.os.SystemClock
import com.filestech.sms.core.crypto.PasswordKdf
import com.filestech.sms.core.crypto.wipe
import com.filestech.sms.data.local.datastore.SecurityStore
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verdict d'une verification de secret. Distingue le refus « mauvais secret » du refus
 * « trop d'essais, patiente » : l'interface doit dire lequel des deux, sinon un compte a
 * rebours en cours passe pour une saisie fausse et l'utilisateur martele le champ.
 *
 * v1.27.10 — introduit par la revue externe GitLab !38458 (constat 3).
 */
sealed interface PinVerdict {
    /** Le secret correspond. */
    data object Ok : PinVerdict

    /**
     * Le secret ne correspond pas — ou aucun secret n'est configure. Les deux cas sont
     * volontairement indistinguables : l'interface affiche un message unique pour ne pas
     * reveler si un hash est pose.
     */
    data object Invalid : PinVerdict

    /** Temporisation en cours ; [untilWall] est un instant d'horloge murale (`System.currentTimeMillis`). */
    data class LockedOut(val untilWall: Long) : PinVerdict
}

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
 * ## v1.27.10 — ce que la revue externe GitLab !38458 a corrigé ici
 *
 * Le threat model ci-dessus était **faux jusqu'à la v1.27.9**, et il l'était par la faute du
 * paragraphe qui le suivait dans ce même fichier : le PIN du coffre pouvait être **remplacé**
 * (Réglages → « Modifier le PIN du coffre ») ou **retiré** (bascule OFF) sans qu'on demande
 * jamais le PIN en place. Un porteur du PIN d'application n'avait donc pas à connaître le
 * second facteur : il le réécrivait. Reproduit sur émulateur Android 14 par le relecteur, et
 * confirmé ligne à ligne côté source.
 *
 * Ce n'était pas un oubli : c'était la porte de sortie documentée pour un PIN de coffre oublié.
 * Mais une porte de sortie qui n'exige rien de plus que le facteur dont on cherche justement à
 * se protéger **est** le contournement, pas un compromis. Depuis :
 *  - [changeVaultPin] et [disableVaultPin] exigent le PIN en place, verifie par le meme
 *    PBKDF2 et sous la meme temporisation que l'entree dans le coffre ;
 *  - la porte de sortie subsiste mais devient **destructive** ([forgetVaultPin]) : elle
 *    supprime le contenu du coffre avant de retirer le PIN. Un porteur du PIN d'application
 *    peut donc detruire, il ne peut plus lire. Detruire est un pouvoir qu'il avait deja
 *    (desinstallation, effacement des donnees) ; lire est celui qu'on lui refuse.
 *
 * **Hors champ** :
 *  - Forensique avec accès Keystore + clé SQLCipher déballée : tout le contenu
 *    de la base est encore chiffré par la même clé maître que le reste. Le PIN
 *    coffre est un GATE UI / domaine, pas une seconde enveloppe crypto. C'est
 *    documenté dans SECURITY.md (cf. § "What this does **not** give you").
 *
 * **Politique crypto** :
 *  - PBKDF2-HMAC-SHA512 avec sel 16B + ≥ 210 000 itérations (calibrate device).
 *  - Stockage dans [SecurityStore] sous le préfixe `vault.*`, indépendant des
 *    clés `pin.*` (app) et `panic.*` (decoy).
 *  - Temporisation exponentielle **dediee**, cf. [verifyVaultPin]. Le raisonnement d'origine
 *    (« le coffre n'est atteignable qu'apres l'unlock app, deja borne par le backoff
 *    d'[AppLockManager], en ajouter un second serait redondant ») valait tant que le coffre
 *    n'etait qu'une porte derriere une autre. Il ne vaut plus des lors que le second facteur
 *    doit resister a quelqu'un qui a **deja** franchi la premiere : ce quelqu'un ne declenche
 *    aucun echec cote application en essayant des PIN de coffre.
 *  - `MessageDigest.isEqual` constant-time pour la comparaison.
 *
 * **Concurrence** : les operations sont suspend + `withContext(io)` ; DataStore garantit
 * l'atomicité read-modify-write sur chaque cle. Pas de mutex applicatif nécessaire.
 */
@Singleton
class VaultPinManager @Inject constructor(
    private val securityStore: SecurityStore,
    private val settings: SettingsRepository,
    private val kdf: PasswordKdf,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * **Première** configuration du PIN coffre. [newPin] est wipé à la sortie.
     * Met à jour le flag `vaultPinEnabled=true` dans `SettingsRepository`
     * pour que l'UI (toggle + `VaultScreen` LaunchedEffect) déclenche le gate.
     *
     * v1.27.10 — **refuse si un PIN garde deja le coffre** et renvoie `false`. C'est la garde
     * de dernier recours du correctif !38458 : meme si un futur appelant rebranchait par
     * megarde le dialogue de creation sur un coffre deja protege, il ne pourrait pas ecraser
     * le secret en place. Le remplacement passe par [changeVaultPin], et lui seul.
     */
    suspend fun configureVaultPin(newPin: CharArray): Boolean = withContext(io) {
        try {
            // Refus si un gate est REELLEMENT actif — hash pose ET flag ON. Un hash sans flag
            // est l'orphelin documente en v1.13.0 (audit NEW-5, `writeVaultPin`) : il ne garde
            // rien, et refuser a cause de lui enfermerait l'utilisateur hors de son coffre sans
            // qu'aucun secret ne soit protege. Les quatre combinaisons hash/flag sont couvertes,
            // cf. `SettingsViewModel.submitVaultPin`.
            val gated = securityStore.vaultPinSnapshot() != null &&
                settings.flow.first().security.vaultPinEnabled
            if (gated) return@withContext false
            writeVaultPin(newPin)
            true
        } finally {
            newPin.wipe()
        }
    }

    /**
     * Remplace le PIN coffre. Exige [current], verifie sous la meme temporisation que
     * [verifyVaultPin]. Les deux tableaux sont wipes a la sortie, y compris en cas de refus.
     *
     * Le succes remet le compteur d'echecs a zero : l'utilisateur vient de prouver qu'il
     * connait le secret.
     */
    suspend fun changeVaultPin(current: CharArray, newPin: CharArray): PinVerdict =
        withContext(io) {
            try {
                when (val verdict = verifyVaultPin(current)) {
                    is PinVerdict.Ok -> {
                        writeVaultPin(newPin)
                        PinVerdict.Ok
                    }
                    else -> verdict
                }
            } finally {
                // `verifyVaultPin` wipe deja `current` ; on ne wipe ici que `newPin`, qui n'a
                // pas ete consomme sur les chemins de refus.
                newPin.wipe()
            }
        }

    /**
     * Desactive le PIN coffre **apres verification de [current]**. Le contenu du coffre reste
     * en place : il redevient simplement atteignable derriere le seul verrou d'application.
     *
     * [current] est wipé à la sortie.
     */
    suspend fun disableVaultPin(current: CharArray): PinVerdict = withContext(io) {
        when (val verdict = verifyVaultPin(current)) {
            is PinVerdict.Ok -> {
                wipeVaultPinState()
                PinVerdict.Ok
            }
            else -> verdict
        }
    }

    /**
     * Porte de sortie « PIN du coffre oublie » — **destructive, et c'est le point**.
     *
     * N'exige aucun secret, donc n'est appelable qu'apres que l'appelant a supprime le contenu
     * du coffre (cf. `SettingsViewModel.forgetVaultPinAndPurge`). L'ordre importe : purger
     * d'abord, ouvrir ensuite. L'inverse laisserait, entre les deux ecritures DataStore, une
     * fenetre ou le coffre est en clair et encore plein.
     */
    suspend fun forgetVaultPin(): Unit = withContext(io) {
        wipeVaultPinState()
    }

    /**
     * Vérifie [candidate] contre le hash stocké. Retourne :
     *  - [PinVerdict.LockedOut] si une temporisation court encore ;
     *  - [PinVerdict.Invalid] si aucun hash n'est configure, ou si le hash ne matche pas ;
     *  - [PinVerdict.Ok] sinon.
     *
     * v1.27.10 — la temporisation croise horloge murale et horloge monotone via
     * [SecurityStore.LockoutSnapshot.isLockoutActive], exactement comme le verrou
     * d'application (audit R7) : avancer l'horloge du telephone ne la raccourcit pas.
     * Les paliers sont ceux d'[AppLockManager.backoffMillis] — reutilises, pas recopies.
     *
     * [candidate] est wipé à la sortie. Le caller (dialog UI) DOIT préparer
     * un fresh CharArray à chaque tentative.
     */
    suspend fun verifyVaultPin(candidate: CharArray): PinVerdict = withContext(io) {
        try {
            val now = System.currentTimeMillis()
            val nowElapsed = SystemClock.elapsedRealtime()
            val lockout = securityStore.vaultLockoutSnapshot()
            if (lockout.isLockoutActive(now, nowElapsed)) {
                return@withContext PinVerdict.LockedOut(lockout.untilWall)
            }
            val snap = securityStore.vaultPinSnapshot() ?: return@withContext PinVerdict.Invalid
            val derived = kdf.derive(candidate, snap.salt, snap.iterations)
            val matches = try {
                java.security.MessageDigest.isEqual(derived, snap.hash)
            } finally {
                derived.wipe()
            }
            if (matches) {
                securityStore.clearVaultLockout()
                PinVerdict.Ok
            } else {
                registerFailure(now, nowElapsed)
            }
        } finally {
            candidate.wipe()
        }
    }

    /**
     * `true` si un hash est posé en store (état post-configuration). Ne lit
     * PAS le flag settings — c'est l'autorité authoritative côté crypto.
     * Permet à l'UI de détecter une incohérence (flag ON mais hash absent)
     * pour proposer un re-set propre.
     */
    suspend fun isVaultPinConfigured(): Boolean = withContext(io) {
        securityStore.vaultPinSnapshot() != null
    }

    /**
     * Millisecondes restantes de temporisation, `0` si aucune. Lue par l'interface pour
     * afficher un compte a rebours plutot qu'un « PIN incorrect » trompeur.
     */
    suspend fun vaultLockoutRemainingMs(): Long = withContext(io) {
        val now = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val snap = securityStore.vaultLockoutSnapshot()
        if (!snap.isLockoutActive(now, nowElapsed)) 0L else (snap.untilWall - now).coerceAtLeast(0L)
    }

    /**
     * Incremente le compteur d'echecs et arme la temporisation une fois le seuil franchi.
     * Meme seuil et memes paliers que le verrou d'application.
     */
    private suspend fun registerFailure(now: Long, nowElapsed: Long): PinVerdict {
        val newFail = (securityStore.vaultFailCount.first() + 1)
            .coerceAtMost(AppLockManager.MAX_FAIL_TRACKED)
        securityStore.setVaultFailCount(newFail)
        if (newFail < AppLockManager.LOCKOUT_THRESHOLD) return PinVerdict.Invalid
        val delayMs = AppLockManager.backoffMillis(newFail - AppLockManager.LOCKOUT_THRESHOLD)
        val until = now + delayMs
        securityStore.setVaultLockout(
            untilWall = until,
            durationMs = delayMs,
            nowElapsed = nowElapsed,
        )
        return PinVerdict.LockedOut(until)
    }

    /**
     * Pose le hash puis le flag. [newPin] n'est **pas** wipe ici — les appelants publics le
     * font dans leur propre `finally`, y compris sur les chemins ou cette fonction n'est
     * jamais atteinte.
     *
     * v1.13.0 audit NEW-5 — le flag est pose DANS le try, apres le hash. Si `settings.update`
     * leve (IOException DataStore rare), le hash est pose mais le flag reste false →
     * [isVaultPinConfigured] detecte l'incoherence et le flux `vaultPinRequired` retourne false
     * (gate desactive). L'utilisateur pourra reconfigurer. Plus sur que l'inverse (flag true
     * sans hash = gate infranchissable).
     */
    private suspend fun writeVaultPin(newPin: CharArray) {
        val salt = kdf.newSalt()
        val iters = kdf.calibrate()
        val hash = kdf.derive(newPin, salt, iters)
        securityStore.setVaultPinHash(salt, hash, iters)
        securityStore.clearVaultLockout()
        settings.update { it.copy(security = it.security.copy(vaultPinEnabled = true)) }
    }

    /**
     * v1.13.0 audit NEW-5 — flip le flag AVANT de retirer le hash. Si
     * [SecurityStore.clearVaultPin] leve, le flag est deja false → le flux `vaultPinRequired`
     * retourne false, le hash residuel est ignore par [isVaultPinConfigured] au prochain check
     * (et reste inoffensif : pas d'auth tant que le flag n'est pas true).
     */
    private suspend fun wipeVaultPinState() {
        settings.update { it.copy(security = it.security.copy(vaultPinEnabled = false)) }
        securityStore.clearVaultPin()
    }
}
