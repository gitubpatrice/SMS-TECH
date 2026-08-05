package com.filestech.sms.security

import android.os.SystemClock
import com.filestech.sms.core.crypto.PasswordKdf
import com.filestech.sms.core.crypto.wipe
import com.filestech.sms.data.local.datastore.SecurityStore
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.security.PanicStateProvider
import com.filestech.sms.domain.settings.LockMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the lock/unlock state of the app. Stores only salted PBKDF2-SHA512 hashes; never the PIN.
 *
 * Lockout policy: monotonic exponential backoff after a streak of failures.
 */
@Singleton
class AppLockManager @Inject constructor(
    private val securityStore: SecurityStore,
    private val settings: SettingsRepository,
    private val kdf: PasswordKdf,
    /**
     * v1.27.2 — porteur de la session du Coffre. Injecté ici pour que [forceLock] puisse tenir
     * l'invariant « application verrouillée ⇒ Coffre verrouillé » en un seul point. Sans
     * dépendance propre, donc aucun cycle avec [VaultManager].
     */
    private val vaultSession: VaultSessionState,
    @IoDispatcher private val io: CoroutineDispatcher,
) : PanicStateProvider {

    /**
     * Initial state is [LockState.Locked] (fail-closed). Any subsequent observer must wait for
     * [resolveInitialState] to flip to [LockState.Disabled] if the user has disabled the lock.
     * This closes F1: the NavHost cannot show the conversation list during the cold-start window
     * (50-300 ms) before settings are loaded from DataStore.
     */
    private val _state = MutableStateFlow<LockState>(LockState.Locked)
    val state: StateFlow<LockState> = _state.asStateFlow()

    override val isPanicDecoyActive: Boolean get() = _state.value is LockState.PanicDecoy

    sealed interface LockState {
        /** Lock is configured OFF in settings — UI is always visible. */
        data object Disabled : LockState
        /** Lock is configured ON and user has not unlocked yet. UI must be hidden. */
        data object Locked : LockState
        /** Too many failed attempts — UI shows the countdown, no PIN accepted yet. */
        data class LockedOut(val until: Long) : LockState
        /** User has unlocked successfully. */
        data object Unlocked : LockState
        /** Panic-code unlock — UI is visible but the vault must remain hidden. */
        data object PanicDecoy : LockState
    }

    /** True iff the UI should be reachable (real unlock OR decoy OR lock disabled). */
    fun isOpenForUi(state: LockState): Boolean = when (state) {
        LockState.Unlocked, LockState.PanicDecoy, LockState.Disabled -> true
        LockState.Locked -> false
        is LockState.LockedOut -> false
    }

    /**
     * Latched once [resolveInitialState] has flipped `_state` away from the fail-closed default.
     * Read by [ensureResolved] to make the resolution idempotent across cold-start contention
     * (Application.onCreate may kick it off asynchronously, while a broadcast receiver fired in
     * the same process may need to wait for the result before consulting the state).
     */
    private val resolvedLatch = AtomicBoolean(false)
    private val resolveMutex = Mutex()

    suspend fun resolveInitialState(): LockState = withContext(io) {
        val s = settings.flow.first()
        val resolved = if (s.security.lockMode == LockMode.OFF) LockState.Disabled else LockState.Locked
        _state.value = resolved
        resolvedLatch.set(true)
        resolved
    }

    /**
     * Idempotent variant of [resolveInitialState]. Safe to call concurrently from multiple
     * coroutines — only the first one actually queries `DataStore`; subsequent callers return
     * immediately once the latch is set.
     *
     * Audit P-P0-5: receivers / services that need a correct `_state` at cold-start (Notification
     * reply, Headless SMS send service) call this before reading [state] / [isOpenForUi]. That
     * way [MainApplication.onCreate] no longer has to block the main thread with `runBlocking` —
     * the receivers each pay the resolution cost lazily in their own coroutine context, and only
     * once per process lifetime.
     */
    suspend fun ensureResolved() {
        if (resolvedLatch.get()) return
        resolveMutex.withLock {
            if (resolvedLatch.get()) return@withLock
            resolveInitialState()
        }
    }

    /**
     * Sets the user's PIN. The [newPin] CharArray is wiped on exit. NEVER round-trips through
     * `toByteArray(UTF-8).toCharArray()` — that was the F3 entropy bug. PBKDF2-HMAC-SHA512 handles
     * the UTF-8 encoding of CharArray internally and preserves the full Unicode range.
     */
    /** Résultat de [setPin] — voir ce dernier pour le détail du refus. */
    sealed interface SetPinOutcome {
        data object Ok : SetPinOutcome

        /**
         * Le PIN proposé est le code panique déjà enregistré. Voir [setPin] : l'accepter
         * enfermerait définitivement l'utilisateur en mode leurre.
         */
        data object SameAsPanicCode : SetPinOutcome
    }

    suspend fun setPin(newPin: CharArray): SetPinOutcome = withContext(io) {
        // v1.27.2 (relecture Gemini du 2026-08-05) — 🔴 ÉVASION DU MODE LEURRE.
        //
        // [clearPin] et [clearPanicCode] refusent tous deux d'agir en session leurre, et le
        // commentaire de [clearPin] explique pourquoi : « masquer un écran est une énumération,
        // le vrai garde est ici ». Ce garde manquait **précisément ici** — le jumeau asymétrique.
        //
        // Sans lui, l'agresseur qui atteint l'écran de changement de code depuis la session leurre
        // écrase le PIN principal, verrouille l'application, la rouvre avec SON code, et sort
        // définitivement du leurre : vraie session, et le Coffre avec.
        //
        // On rend `Ok` plutôt qu'un refus : dans une session leurre, tout est déception par
        // construction. Un message d'erreur apprendrait à l'agresseur qu'il existe une session
        // réelle derrière celle qu'il voit — exactement ce que le leurre existe pour cacher.
        // Aucun secret n'est écrit, l'utilisateur légitime retrouve son PIN intact.
        if (_state.value is LockState.PanicDecoy) {
            newPin.wipe()
            return@withContext SetPinOutcome.Ok
        }
        try {
            // v1.26.1 (audit C3) — refus SYMÉTRIQUE de celui de [setPanicCode].
            //
            // `setPanicCode` refuse depuis la v1.26.0 un code identique au PIN, et documente
            // pourquoi : [attemptUnlock] évalue le code panique AVANT le PIN, donc un code
            // identique fait systématiquement gagner le leurre et l'utilisateur n'a plus AUCUN
            // moyen d'ouvrir son application normalement — enfermement définitif, coffre compris.
            //
            // Le refus manquait dans l'autre sens : rien n'empêchait de CHANGER SON PIN pour la
            // valeur de son code panique, ce qui produit exactement le même enfermement par la
            // porte d'à côté. Le picker rouvre le dialogue de saisie même quand le mode est déjà
            // PIN, et un utilisateur qui « recycle » un code mémorisé tombe droit dedans.
            //
            // Vérification faite AVANT `kdf.calibrate()` : inutile de payer la calibration pour
            // un candidat qu'on va refuser.
            val panicSnap = securityStore.panicSnapshot()
            if (panicSnap != null &&
                matches(newPin, panicSnap.salt, panicSnap.hash, panicSnap.iterations)
            ) {
                return@withContext SetPinOutcome.SameAsPanicCode
            }
            val salt = kdf.newSalt()
            val iters = kdf.calibrate()
            val hash = kdf.derive(newPin, salt, iters)
            securityStore.setPinHash(salt, hash, iters)
        } finally {
            // Un seul effacement, dans le `finally` : il couvre le retour anticipé du refus
            // comme le chemin nominal.
            newPin.wipe()
        }
        settings.update { it.copy(security = it.security.copy(lockMode = LockMode.PIN)) }
        _state.value = LockState.Locked
        SetPinOutcome.Ok
    }

    suspend fun clearPin() = withContext(io) {
        // v1.26.1 (audit C1) — refus en session leurre, garde côté ACCÈS.
        //
        // La ligne « Verrouillage de l'app » est désormais masquée en `PanicDecoy`, mais masquer
        // un écran est une énumération : elle vieillit à chaque point d'entrée ajouté. Le vrai
        // garde est ici. Sans lui, l'agresseur retirait le verrou depuis la session leurre, ce
        // qui posait `LockState.Disabled` — et comme TOUTES les gardes du leurre testent
        // `is PanicDecoy`, elles tombaient toutes d'un coup. Le désarmement doit venir de
        // l'utilisateur légitime, dans sa vraie session.
        if (_state.value is LockState.PanicDecoy) return@withContext
        securityStore.clearPin()
        settings.update { it.copy(security = it.security.copy(lockMode = LockMode.OFF)) }
        _state.value = LockState.Disabled
        // v1.26.0 — sans PIN principal, un code panique n'a plus de sens : il deviendrait le seul
        // secret connu et ouvrirait l'app en leurre définitif, sans aucun moyen d'en sortir.
        securityStore.clearPanic()
    }

    /** Résultat de [setPanicCode] — voir ce dernier pour le détail des refus. */
    sealed interface PanicCodeOutcome {
        data object Ok : PanicCodeOutcome

        /** Aucun PIN principal n'est configuré : il n'y aurait rien vers quoi revenir. */
        data object NoPrimaryPin : PanicCodeOutcome

        /** Le code proposé est le PIN principal. Voir ci-dessous : il faut le refuser. */
        data object SameAsPin : PanicCodeOutcome
    }

    /**
     * v1.26.0 — définit le code panique. Le [newCode] est effacé sur tous les chemins de sortie.
     *
     * Cette fonction manquait, et **son absence rendait tout le mode leurre inatteignable** :
     * `setPanicCode` existait dans le magasin, `panicSnapshot()` était lu à chaque déverrouillage,
     * `LockState.PanicDecoy` et ses gardes étaient complets — mais rien n'écrivait jamais le code,
     * donc l'instantané valait toujours `null` et la comparaison échouait toujours. La chaîne
     * « PIN de secours (mode panique) » existait aussi, sans écran pour l'afficher ; lint la
     * signalait comme inutilisée et l'avertissement avait été absorbé par la baseline.
     *
     * Deux refus, pour des raisons opposées :
     *
     * - **[PanicCodeOutcome.SameAsPin]** — [attemptUnlock] évalue le code panique **avant** le PIN
     *   (audit P1-3, v1.2.0). Un code identique ferait donc systématiquement gagner le leurre :
     *   l'utilisateur n'aurait plus **aucun** moyen d'ouvrir son application normalement, et le
     *   coffre lui deviendrait inaccessible à lui aussi. C'est un enfermement définitif, pas une
     *   gêne — d'où le refus.
     * - **[PanicCodeOutcome.NoPrimaryPin]** — symétrique : sans PIN principal, le code panique
     *   serait le seul secret connu, et l'application s'ouvrirait en leurre pour toujours.
     *
     * Le dérivé est le même que celui du PIN (PBKDF2-HMAC-SHA512, sel neuf, itérations calibrées
     * sur l'appareil) : un code panique plus faible à casser trahirait la contrainte qu'il protège.
     */
    suspend fun setPanicCode(newCode: CharArray): PanicCodeOutcome = withContext(io) {
        try {
            val pinSnap = securityStore.pinSnapshot()
                ?: return@withContext PanicCodeOutcome.NoPrimaryPin
            if (matches(newCode, pinSnap.salt, pinSnap.hash, pinSnap.iterations)) {
                return@withContext PanicCodeOutcome.SameAsPin
            }
            val salt = kdf.newSalt()
            val iters = kdf.calibrate()
            securityStore.setPanicCode(salt, kdf.derive(newCode, salt, iters), iters)
            PanicCodeOutcome.Ok
        } finally {
            // Un seul effacement, dans le `finally` : il couvre les deux retours anticipés
            // au-dessus comme le chemin nominal.
            newCode.wipe()
        }
    }

    /** v1.26.0 — retire le code panique. Le PIN principal et le reste sont intacts. */
    suspend fun clearPanicCode() = withContext(io) {
        // v1.27.2 (relecture Gemini 2026-08-05, finding 4) — MÊME garde que [clearPin], dont
        // celle-ci était le jumeau resté sans protection.
        //
        // Sans elle, quelqu'un ayant contraint l'utilisateur à ouvrir la session leurre pouvait
        // supprimer le code panique depuis les Réglages. Le coffre n'en était pas exposé, mais la
        // victime perdait sa seule porte de sortie sous contrainte — désarmée par l'agresseur
        // lui-même, dans la session censée le tenir à distance.
        //
        // L'écran est peut-être masqué en session leurre ; le KDoc de [clearPin] dit déjà pourquoi
        // cela ne suffit pas : « masquer un écran est une énumération, elle vieillit à chaque
        // point d'entrée ajouté ». Le garde qui compte est ici.
        if (_state.value is LockState.PanicDecoy) return@withContext
        securityStore.clearPanic()
    }

    /** v1.26.0 — vrai si un code panique est enregistré. Sert à l'affichage des Réglages. */
    suspend fun isPanicCodeSet(): Boolean = withContext(io) { securityStore.panicSnapshot() != null }

    /**
     * Promotes the lock mode to [LockMode.BIOMETRIC] **on top of an existing PIN**. The PIN is
     * kept as the fallback secret of record — if the biometric becomes unavailable (re-enrolled
     * empties the key, dirty sensor, hardware failure) the user can still unlock with the PIN
     * they configured. Refuses to switch when no PIN is set: a biometric-only mode would
     * lock the user out the moment their fingerprint enrollment changes.
     */
    suspend fun enableBiometric(): Boolean = withContext(io) {
        if (securityStore.pinSnapshot() == null) return@withContext false
        settings.update { it.copy(security = it.security.copy(lockMode = LockMode.BIOMETRIC)) }
        true
    }

    /**
     * Drops back from BIOMETRIC to PIN (keeps the PIN). No-op outside BIOMETRIC.
     *
     * v1.3.5 G9 audit fix — read-then-update remplacé par un transform atomique
     * direct sur `settings.update`. Avant : `settings.flow.first()` puis `update`
     * laissait une fenêtre où 2 callers concurrents pouvaient lire la même valeur
     * initiale. Bénin (transform idempotent) mais anti-idiomatique pour DataStore
     * qui garantit l'atomicité read-modify-write avec `update`.
     */
    suspend fun disableBiometric() = withContext(io) {
        settings.update { current ->
            if (current.security.lockMode == LockMode.BIOMETRIC) {
                current.copy(security = current.security.copy(lockMode = LockMode.PIN))
            } else {
                current // no-op transform : DataStore détecte et ne re-écrit pas
            }
        }
    }

    /**
     * v1.26.1 (audit H17) — enveloppe qui EFFACE le candidat sur tous les chemins de sortie.
     *
     * `setPin`, `setPanicCode`, `VaultPinManager.verifyVaultPin` et `PinEntryDialog` effacent
     * tous leur `CharArray` dans un `finally` ; `attemptUnlock` était le seul consommateur de
     * secret à ne pas le faire, alors qu'il reçoit le PIN principal — celui qui ouvre tout,
     * coffre compris — et qu'il est appelé à chaque déverrouillage. `matches()` n'efface que
     * le dérivé, et l'appelant (`LockScreen`) construit un `CharArray` que personne ne reprend :
     * le PIN en clair survivait donc dans le tas jusqu'à la prochaine GC, lisible par un heap
     * dump. L'enveloppe couvre les cinq retours anticipés du corps sans y toucher.
     */
    suspend fun attemptUnlock(candidate: CharArray): LockState =
        try {
            attemptUnlockInternal(candidate)
        } finally {
            candidate.wipe()
        }

    private suspend fun attemptUnlockInternal(candidate: CharArray): LockState = withContext(io) {
        val now = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        // Audit R7 (v1.14.8) — Snapshot mono+wall vs simple wall check. Si mono dit "encore
        // en lockout" (immune à la manipulation horloge), on respecte le lockout même si wall
        // dit "expiré". Fallback wall si reboot détecté (nowElapsed < setAtElapsed).
        val lockoutSnap = securityStore.lockoutSnapshot()
        if (lockoutSnap.isLockoutActive(now, nowElapsed)) {
            return@withContext LockState.LockedOut(lockoutSnap.untilWall).also { _state.value = it }
        }

        // Audit P1-3 (v1.2.0): both PIN and panic snapshots are evaluated and the failure
        // counter is incremented **once** per attempt if neither matches. Earlier the panic
        // branch returned before the failure path, letting an attacker brute-force a 4-digit
        // panic code while the long PIN absorbed the lockout — ~10 000 panic probes possible
        // without ever tripping the exponential cool-down.
        val panicSnap = securityStore.panicSnapshot()
        val panicMatches = panicSnap != null &&
            matches(candidate, panicSnap.salt, panicSnap.hash, panicSnap.iterations)
        if (panicMatches) {
            _state.value = LockState.PanicDecoy
            securityStore.setFailCount(0)
            securityStore.setLastUnlock(now)
            return@withContext LockState.PanicDecoy
        }

        val snap = securityStore.pinSnapshot()
            ?: return@withContext LockState.Disabled.also { _state.value = it }

        if (matches(candidate, snap.salt, snap.hash, snap.iterations)) {
            securityStore.setFailCount(0)
            securityStore.clearLockout()
            securityStore.setLastUnlock(now)
            _state.value = LockState.Unlocked
            LockState.Unlocked
        } else {
            val newFail = (securityStore.failCount.first() + 1).coerceAtMost(MAX_FAIL_TRACKED)
            securityStore.setFailCount(newFail)
            if (newFail >= LOCKOUT_THRESHOLD) {
                val delayMs = backoffMillis(newFail - LOCKOUT_THRESHOLD)
                val until = now + delayMs
                // Audit R7 — Persiste wall + mono baseline + durée pour défense en profondeur
                // contre la manipulation de l'horloge système.
                securityStore.setLockout(untilWall = until, durationMs = delayMs, nowElapsed = nowElapsed)
                _state.value = LockState.LockedOut(until)
                LockState.LockedOut(until)
            } else {
                _state.value = LockState.Locked
                LockState.Locked
            }
        }
    }

    /**
     * v1.26.0 — repasse de [LockState.LockedOut] à [LockState.Locked] quand le blocage a
     * réellement expiré. Rend vrai si l'état a changé.
     *
     * **Sans cette fonction, l'utilisateur restait bloqué pour toujours.** L'écran désactive le
     * bouton pendant `LockedOut` (audit I1, v1.14.8) et affiche un compte à rebours (audit R5),
     * mais rien ne faisait retomber l'état une fois le délai écoulé : `_state` ne changeait qu'au
     * prochain `attemptUnlock`… lui-même inatteignable puisque le bouton était désactivé. Le
     * compte à rebours atteignait zéro et la saisie restait morte.
     *
     * ⚠️ On ne se fie **pas** au minuteur de l'interface : il suffirait d'avancer l'horloge du
     * téléphone pour effacer la temporisation exponentielle. C'est l'instantané persistant qui
     * tranche — il croise horloge murale et horloge monotone (audit R7, v1.14.8), cette dernière
     * étant insensible à la manipulation. Si le blocage court toujours, on ne touche à rien.
     */
    suspend fun refreshLockoutIfExpired(): Boolean = withContext(io) {
        if (_state.value !is LockState.LockedOut) return@withContext false
        val snap = securityStore.lockoutSnapshot()
        if (snap.isLockoutActive(System.currentTimeMillis(), SystemClock.elapsedRealtime())) {
            return@withContext false
        }
        _state.value = LockState.Locked
        true
    }

    /**
     * Forces the app back to its locked state. Idempotent on [LockState.Disabled].
     * PanicDecoy is also re-locked: the next unlock attempt re-evaluates panic vs primary PIN.
     */
    fun forceLock() {
        val current = _state.value
        if (current != LockState.Disabled) _state.value = LockState.Locked
        // v1.27.2 (relecture Gemini du 2026-08-05) — 🔴 LE COFFRE SURVIVAIT AU VERROUILLAGE.
        //
        // `AutoLockObserver` ne verrouillait le Coffre que si `lockVaultOnLeave` était coché — un
        // réglage de CONFORT, prévu pour basculer un instant sur une autre application sans
        // redemander le second facteur. Décoché, l'application se verrouillait mais la session du
        // Coffre restait OUVERTE : quiconque rouvrait ensuite avec le PIN principal trouvait le
        // Coffre déverrouillé. **Le second facteur était purement et simplement contourné.**
        //
        // Même motif que le défaut corrigé en v1.25.4 (« le Coffre s'ouvrait AVANT son second
        // facteur ») : la garde était sur l'affichage, pas sur l'accès.
        //
        // La garantie est posée ICI et non chez l'appelant, pour la même raison que l'alarme du
        // Safety call : `forceLock` a plusieurs appelants, et en câbler tous sauf un est le motif
        // de défaut qui revient le plus souvent sur ce projet. Ici, l'invariant « application
        // verrouillée ⇒ Coffre verrouillé » vaut pour tout appelant, y compris ceux qu'on
        // ajoutera plus tard.
        //
        // [VaultSessionState] n'a AUCUNE dépendance — elle a été extraite exactement pour ça —
        // donc aucun cycle avec [VaultManager], qui dépend de ce gestionnaire.
        vaultSession.lock()
    }

    // -------- Biometric handshake (fixes F2 audit finding) ------------------------------------
    // A one-shot, single-use challenge token is issued by [beginBiometricChallenge], passed to the
    // BiometricPrompt's CryptoObject success callback site, and verified by [markBiometricUnlocked].
    // Without a valid live challenge, [markBiometricUnlocked] is a no-op — making the function safe
    // even if accidentally called from a regression / hostile code path.
    //
    // Audit P1-2 (v1.2.0): challenge stored in an [AtomicReference] so that two concurrent
    // begin/mark calls (rotation race, double-tap on the lock screen) cannot end up with a stale
    // token. The previous `@Volatile var` had atomic read/write *each*, but the swap-and-return
    // pair in [markBiometricUnlocked] was not atomic across the two — a second `begin` between
    // the read and the reset of the first call would have caused either path to swallow the
    // unlock silently. `getAndSet(null)` makes the consume both atomic and one-shot.
    private val biometricChallenge = java.util.concurrent.atomic.AtomicReference<ByteArray?>(null)
    private val biometricRng = java.security.SecureRandom()

    /**
     * Audit R6 (v1.14.8) — Double-tap guard. Si un challenge est déjà en vol (prompt biométrie
     * pas encore consommé), on RÉUTILISE le même token au lieu d'écraser. Avant : un double-tap
     * rapide produisait token A puis token B (écrasement), si la 1ère réponse arrivait elle
     * trouvait `getAndSet(null)=B≠A` → échec silencieux + l'user devait taper une 3ème fois.
     * Maintenant les deux prompts partagent le même challenge → la 1ère consommation réussit,
     * la 2ème no-op proprement (state déjà Unlocked, ou challenge déjà null).
     */
    fun beginBiometricChallenge(): ByteArray {
        biometricChallenge.get()?.let { return it.copyOf() }
        val token = ByteArray(BIO_CHALLENGE_BYTES).also(biometricRng::nextBytes)
        if (!biometricChallenge.compareAndSet(null, token)) {
            // Race window perdue : un autre caller a posé un token. Retourne le sien.
            return biometricChallenge.get()?.copyOf() ?: token.copyOf()
        }
        return token.copyOf()
    }

    /**
     * Promotes the session to [LockState.Unlocked] **only** when the current state is
     * [LockState.Locked]. Refuses to act in any other state — in particular [LockState.LockedOut]
     * (audit S-P0-3: a biometric success must not bypass the exponential cool-down imposed on PIN
     * failures) and [LockState.PanicDecoy] (a biometric scan from the panic session must never
     * unseal it). [LockState.Unlocked] and [LockState.Disabled] are likewise no-ops.
     *
     * The state check is sufficient — `_state` is the authoritative live value, kept up to date
     * by [attemptUnlock] / [forceLock] / [resolveInitialState]. We deliberately do not re-read
     * [SecurityStore.lockoutUntil] here: it would force the callback (BiometricPrompt success) to
     * become suspending for no real gain, and the conservative path of waiting for the user to
     * tap PIN — which DOES go through [attemptUnlock] and refreshes the state — handles the rare
     * "lockout has just expired but state is stale" edge cleanly.
     */
    fun markBiometricUnlocked(challenge: ByteArray) {
        // Atomic consume: the challenge is single-use. If a second prompt fires before the
        // first completes, only one of the two can succeed; the other gets `null` and bails.
        val expected = biometricChallenge.getAndSet(null) ?: return
        if (!java.security.MessageDigest.isEqual(expected, challenge)) return
        if (_state.value !is LockState.Locked) return
        _state.value = LockState.Unlocked
    }

    private fun matches(candidate: CharArray, salt: ByteArray, expected: ByteArray, iters: Int): Boolean {
        val derived = kdf.derive(candidate, salt, iters)
        return try {
            constantTimeEquals(derived, expected)
        } finally {
            derived.wipe()
        }
    }

    companion object {
        const val LOCKOUT_THRESHOLD = 5
        const val MAX_FAIL_TRACKED = 100
        const val BIO_CHALLENGE_BYTES = 32

        /**
         * Backoff after [LOCKOUT_THRESHOLD] failures (fixes F39). Starts at 5 s — long enough to
         * make 4-digit PIN brute-force costly without being punitive on a typo. Caps at 5 minutes.
         * 1000 PINs × 5 s = ~83 minutes minimum if the user keeps hammering through every step.
         */
        private val BACKOFF_STEPS_MS = longArrayOf(
            5_000, 10_000, 30_000, 60_000, 120_000, 300_000,
        )
        fun backoffMillis(stepIndex: Int): Long =
            BACKOFF_STEPS_MS[stepIndex.coerceIn(0, BACKOFF_STEPS_MS.size - 1)]

        private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
            if (a.size != b.size) return false
            var r = 0
            for (i in a.indices) r = r or (a[i].toInt() xor b[i].toInt())
            return r == 0
        }
    }
}
