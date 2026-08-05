package com.filestech.sms.security

import com.filestech.sms.core.crypto.KeystoreManager
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.vault.VaultMover
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Logical "secure vault": conversations marked as `in_vault=true` are hidden from the main UI
 * and only readable while the app is fully unlocked AND the vault has been opened in this
 * session.
 *
 * # Threat model — what this gives you today (audit S-P1-1)
 *
 *  - The whole Room DB is encrypted at rest by SQLCipher with a key wrapped in the Android
 *    Keystore, so anyone reading the on-device files (adb pull, USB debugging, root) sees
 *    only ciphertext.
 *  - On top of that, the vault adds **three layered UI / data gates** so that a coerced
 *    "open the app" scenario (panic-decoy unlock, shoulder surfing) does not expose hidden
 *    conversations:
 *    1. [ConversationRepositoryImpl.observeVault]/`observeOne`/`observeMessages` return
 *       empty when `lockState is PanicDecoy` and the conversation is in the vault.
 *    2. [com.filestech.sms.ui.AppRoot] redirects any nav to the Vault route while in decoy.
 *    3. [com.filestech.sms.ui.screens.conversations.ConversationsScreen] hides the vault
 *       top-bar entry point in decoy.
 *
 * # What this does **not** give you (yet)
 *
 *  - A separate cryptographic envelope. Vault messages share the same SQLCipher key as
 *    regular messages, so an attacker who already has the master key (rooted device with
 *    Keystore access) reads everything in one pass.
 *  - Per-session re-authentication of vault content. Once the master PIN is correct, the
 *    Keystore-wrapped key decrypts every row.
 *
 * A real second envelope using [com.filestech.sms.core.crypto.KeystoreManager.ALIAS_VAULT_KEK]
 * with `setUserAuthenticationRequired = true` is reserved for v1.1.1 because it requires a
 * Room schema migration to add an encrypted-body column and a biometric / device-credential
 * UX. The alias is already created at install time so the migration path is forward-compatible.
 */
@Singleton
class VaultManager @Inject constructor(
    private val keystore: KeystoreManager,
    private val conversationRepo: ConversationRepository,
    private val appLock: AppLockManager,
    // v1.26.1 (audit H1) — état de session extrait ici pour être observable ET consultable
    // par la couche données sans cycle de dépendances. Voir [VaultSessionState].
    private val session: VaultSessionState,
    @IoDispatcher private val io: CoroutineDispatcher,
) : VaultMover {

    // v1.11.0 audit SEC-V2 — AtomicBoolean au lieu de `@Volatile Boolean`.
    // Sémantique correcte pour un flag partagé entre coroutines IO et UI :
    // get/set atomiques garantis (la JVM peut tearer un boolean Volatile sur
    // certaines architectures 32-bit, et le compareAndSet n'est pas disponible
    // sur un primitive Volatile). Le flag continue à servir de simple
    // mémoire d'état (pas de logique transactionnelle CAS) — mais le
    // contrat est désormais formel.
    //
    // v1.26.1 (audit H1) — l'état a déménagé dans [VaultSessionState], qui conserve cette
    // sémantique (`MutableStateFlow.value` est atomique et visible entre threads) et ajoute
    // les deux propriétés qui manquaient : il est OBSERVABLE, et il est atteignable par la
    // couche données sans fermer le cycle `VaultManager → ConversationRepository`.
    // L'API publique ci-dessous est inchangée : aucun appelant n'est touché.

    val isVaultUnlockedInSession: Boolean get() = session.isUnlocked

    /**
     * v1.27.2 — le même état, mais **observable**.
     *
     * Constaté sur appareil le 2026-08-05 : le Coffre ouvert, l'application passée en
     * arrière-plan puis rouverte, l'écran affichait **« le coffre est vide »**. Le contenu était
     * bien protégé — `observeVault` rend une liste vide dès que la session est fermée, et les
     * données sont intactes en base — mais l'écran, lui, gardait un état déverrouillé **local**
     * figé par un `remember`, lu une seule fois à l'entrée.
     *
     * Il croyait donc être encore ouvert, recevait une liste vide, et annonçait un coffre vide au
     * lieu de redemander le second facteur. La garde était sur l'accès, l'affichage mentait — et
     * il mentait dans le sens le plus inquiétant qui soit pour l'utilisateur : celui de la perte
     * de données.
     */
    val unlockedInSession: StateFlow<Boolean> get() = session.unlocked

    /** Marks the vault as opened for this app session. Caller must already be authenticated. */
    fun markUnlocked() { session.markUnlocked() }

    /** Forces the vault to be locked again (e.g. on background or panic). */
    fun lock() { session.lock() }

    /**
     * Moves a conversation **into** the vault.
     *
     * Audit S-P0-2: this operation must require the vault to be unlocked in the current session,
     * symmetrically with [moveOutOfVault]. Without the guard, a panic-decoy session — which
     * intentionally leaves the rest of the UI usable — could be tricked into bulk-hiding the
     * user's regular conversations behind a vault the legitimate user can no longer reach
     * (because they don't know the decoy's "primary" PIN, since there isn't one — decoy unlocks
     * only the decoy view). Even if no malicious flow exists today, the asymmetry was a latent
     * footgun.
     */
    override suspend fun moveToVault(conversationId: Long): Outcome<Unit> = withContext(io) {
        if (!session.isUnlocked) return@withContext Outcome.Failure(AppError.Locked())
        conversationRepo.moveToVault(conversationId, true)
        Outcome.Success(Unit)
    }

    override suspend fun moveOutOfVault(conversationId: Long): Outcome<Unit> = withContext(io) {
        if (!session.isUnlocked) return@withContext Outcome.Failure(AppError.Locked())
        conversationRepo.moveToVault(conversationId, false)
        Outcome.Success(Unit)
    }

    /**
     * v1.11.0 — move-in/out depuis l'extérieur de [com.filestech.sms.ui.screens.vault.VaultScreen]
     * (long-press conv liste, overflow ThreadScreen). Préserve le guard
     * [sessionUnlocked] historique tout en débloquant l'UX manquante : l'user
     * authentifié principal (non-decoy) doit pouvoir déplacer une conv dans le
     * coffre depuis n'importe où, et inversement depuis le VaultScreen quand
     * sessionUnlocked est déjà true.
     *
     * **Politique de sécurité** :
     *  - Refuse si [AppLockManager.LockState.PanicDecoy] — un agresseur en
     *    decoy NE DOIT PAS pouvoir bulk-hider les conv légitimes (S-P0-2).
     *    Defense in depth : l'UI doit DÉJÀ masquer le menu en decoy.
     *  - Refuse si [AppLockManager.LockState.Locked] — état impossible côté
     *    UI (l'écran de lock bloque la nav) mais filet de sécurité.
     *  - v1.27.2 (audit externe 2026-08-04 #3) : ne marque PLUS la session coffre
     *    déverrouillée. L'auto-`markUnlocked()` datait de la v1.11.0, AVANT le PIN coffre
     *    (v1.13.0) : depuis, il contournait le second facteur — déplacer n'importe quelle
     *    conversation vers le coffre depuis la liste principale ouvrait ensuite le Coffre
     *    sans PIN coffre ni biométrie, [com.filestech.sms.ui.screens.vault.VaultScreen]
     *    initialisant `unlocked` et `vaultPinPassed` depuis cette session.
     *  - Déplacer VERS le coffre ne requiert aucun droit sur son contenu (cacher est sûr).
     *    En SORTIR révèle du contenu protégé : même exigence que [moveOutOfVault], session
     *    coffre déverrouillée obligatoire. La garde d'affichage (fil masqué tant que la
     *    session est fermée) ne suffit pas — c'est l'ACCÈS qui doit être gardé.
     */
    override suspend fun requestMoveToVault(
        conversationId: Long,
        intoVault: Boolean,
    ): Outcome<Unit> = withContext(io) {
        // v1.26.1 (audit B1) — on interroge le PRÉDICAT qui fait autorité, au lieu d'énumérer
        // les états fermés.
        //
        // L'énumération oubliait `LockedOut` : le commentaire disait « Unlocked or Disabled »,
        // mais un blocage après trop de tentatives tombait aussi dans le `else` — et déplaçait
        // donc une conversation vers le coffre EN POSANT `sessionUnlocked = true`. Aucun chemin
        // atteignable n'a été trouvé (les écrans appelants sont dépilés dès que le verrou
        // s'affiche), mais c'est exactement la forme qui a produit la moitié des défauts de cet
        // audit : une liste d'états qui vieillit mal. `isOpenForUi` est la source de vérité
        // utilisée par les receveurs et le service headless — on s'y aligne.
        val lockState = appLock.state.value
        if (!appLock.isOpenForUi(lockState) || lockState is AppLockManager.LockState.PanicDecoy) {
            return@withContext Outcome.Failure(AppError.Locked())
        }
        // v1.11.0 audit S2 — re-check juste avant la mutation pour bloquer
        // une race où PanicDecoy aurait été activé entre la 1ère évaluation
        // et l'exécution de la coroutine sur IO. Sans ce filet, une notif
        // OS poussant PanicDecoy juste avant `moveToVault` cacherait des
        // conv légitimes sous une session decoy (bulk-hiding latent).
        val postCheck = appLock.state.value
        if (postCheck is AppLockManager.LockState.PanicDecoy) {
            return@withContext Outcome.Failure(AppError.Locked())
        }
        // v1.27.2 (audit externe 2026-08-04 #3) — sortir du coffre exige le second facteur ;
        // y entrer, non. Et plus d'auto-`markUnlocked()` : cf. la politique ci-dessus.
        if (!intoVault && !session.isUnlocked) {
            return@withContext Outcome.Failure(AppError.Locked())
        }
        conversationRepo.moveToVault(conversationId, intoVault)
        Outcome.Success(Unit)
    }

    /**
     * v1.14.8 audit R8 — Bulk move atomique. Replace l'ancienne boucle itérative dans
     * `VaultScreen.bulkMoveSelectedOut` qui appelait `requestMoveToVault` N fois :
     *  - non atomique (process-kill au milieu = état partiel non-récupérable)
     *  - feedback "moved out N" potentiellement incorrect en cas d'échec partiel
     *
     * Garde les mêmes guards PanicDecoy/Locked (pré + re-check post context-switch), puis
     * délègue à [ConversationRepository.bulkMoveToVault] qui wrap dans `withTransaction`.
     * Retourne `Outcome.Success(count)` avec le nombre réel de rows mises à jour.
     *
     * v1.27.2 (audit externe 2026-08-04 #3) — même politique que [requestMoveToVault] : plus
     * d'auto-`markUnlocked()` (il contournait le second facteur du Coffre), et la sortie du
     * coffre exige une session coffre déjà déverrouillée.
     */
    override suspend fun requestBulkMoveToVault(
        ids: List<Long>,
        intoVault: Boolean,
    ): Outcome<Int> = withContext(io) {
        if (ids.isEmpty()) return@withContext Outcome.Success(0)
        // v1.27.2 (audit de cohérence 2026-08-04) — MÊME prédicat que [requestMoveToVault], et
        // non plus une énumération d'états.
        //
        // La v1.26.1 (audit B1) avait remplacé cette énumération par `isOpenForUi` dans la
        // jumelle non-bulk, précisément parce qu'elle OUBLIAIT `LockedOut` : un blocage après
        // trop de tentatives n'est ni `Locked` ni `PanicDecoy`, il tombait donc dans le `else`
        // et laissait l'opération passer. Le correctif n'avait pas été porté ici, alors que le
        // KDoc de cette fonction affirme « garde les MÊMES guards » — un commentaire qui
        // promettait ce que son code ne tenait pas.
        //
        // Aucun chemin d'interface atteignable identifié aujourd'hui (l'écran de verrouillage
        // dépile la navigation dès qu'il s'affiche) : c'est de la défense en profondeur, le
        // même argument qui a justifié de corriger la jumelle. `isOpenForUi` est le prédicat
        // qui fait autorité — une énumération d'états vieillit mal, celle-ci l'a prouvé deux
        // fois.
        val pre = appLock.state.value
        if (!appLock.isOpenForUi(pre) || pre is AppLockManager.LockState.PanicDecoy) {
            return@withContext Outcome.Failure(AppError.Locked())
        }
        // Re-check anti-race PanicDecoy après context-switch IO (cohérent avec
        // [requestMoveToVault]).
        val post = appLock.state.value
        if (post is AppLockManager.LockState.PanicDecoy) {
            return@withContext Outcome.Failure(AppError.Locked())
        }
        // v1.27.2 (audit externe 2026-08-04 #3) — cf. [requestMoveToVault] : sortir du coffre
        // exige le second facteur, et plus d'auto-`markUnlocked()`.
        if (!intoVault && !session.isUnlocked) {
            return@withContext Outcome.Failure(AppError.Locked())
        }
        val updated = conversationRepo.bulkMoveToVault(ids, intoVault)
        Outcome.Success(updated)
    }

    /** Ensures the underlying Keystore alias exists. Called at first vault use. */
    fun ensureKey() {
        keystore.getOrCreateKey(KeystoreManager.ALIAS_VAULT_KEK)
    }
}
