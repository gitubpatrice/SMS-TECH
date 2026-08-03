package com.filestech.sms.ui.screens.lock

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.withResumed
import com.filestech.sms.R
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.domain.settings.LockMode
import com.filestech.sms.security.AppLockManager
import com.filestech.sms.ui.security.StrongBiometrics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class LockViewModel @Inject constructor(
    val appLock: AppLockManager,
    settings: SettingsRepository,
    private val biometricGate: com.filestech.sms.ui.security.BiometricGate,
) : ViewModel() {

    /**
     * v1.25.3 — prépare la clé Keystore adossée à la biométrie. Suspend : la génération touche le
     * matériel sécurisé et n'a rien à faire sur le thread principal.
     */
    suspend fun prepareBiometricGate() = biometricGate.prepare()

    /** Consomme le `Cipher` rendu par le prompt — c'est lui qui fait preuve, pas le callback. */
    fun confirmBiometricGate(cipher: javax.crypto.Cipher?): Boolean = biometricGate.confirm(cipher)

    /** Current lock mode — drives whether the biometric prompt fires at screen entry. */
    val lockMode: StateFlow<LockMode> = settings.flow
        .map { it.security.lockMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), LockMode.OFF)

    /**
     * v1.26.0 — expose l'échec d'une tentative pour que l'écran le dise.
     *
     * Jusqu'ici un code erroné ne produisait **aucun retour** : le champ se vidait et l'écran
     * restait identique. Impossible de distinguer « je me suis trompé » de « l'application n'a
     * pas réagi ».
     *
     * ⚠️ Seul `Locked` compte comme échec. Les autres états ne doivent **jamais** allumer ce
     * message :
     * - `PanicDecoy` est un **succès** — afficher une erreur trahirait l'existence du code
     *   panique à quiconque regarde par-dessus l'épaule, ce qui ruinerait le mode leurre ;
     * - `LockedOut` a déjà son compte à rebours dédié, en dessous ;
     * - `Unlocked` / `Disabled` sont des ouvertures.
     */
    private val _wrongCode = kotlinx.coroutines.flow.MutableStateFlow(false)
    val wrongCode: StateFlow<Boolean> = _wrongCode

    fun attempt(pin: CharArray) {
        viewModelScope.launch {
            _wrongCode.value = appLock.attemptUnlock(pin) is AppLockManager.LockState.Locked
        }
    }

    /** Efface le message dès que l'utilisateur ressaisit quelque chose. */
    fun clearWrongCode() { _wrongCode.value = false }

    /**
     * v1.26.0 — appelé quand le compte à rebours du blocage atteint zéro. Voir
     * [AppLockManager.refreshLockoutIfExpired] : c'est lui qui décide, pas le minuteur.
     */
    /**
     * v1.26.1 (audit M1) — SUSPEND et rend le verdict, au lieu de lancer une coroutine détachée.
     *
     * L'écran a besoin de savoir si le blocage est réellement levé : `refreshLockoutIfExpired`
     * peut légitimement refuser (l'instantané persistant croise horloge murale et monotone). Un
     * appel « tire et oublie » ne permettait pas de re-sonder, et l'écran restait figé sur un
     * compte à rebours à zéro avec un bouton mort.
     */
    suspend fun onLockoutCountdownFinished(): Boolean = appLock.refreshLockoutIfExpired()

    fun beginBiometricChallenge(): ByteArray = appLock.beginBiometricChallenge()
    fun markBiometricUnlocked(token: ByteArray) = appLock.markBiometricUnlocked(token)

    /**
     * Called when the system reports the biometric key is permanently invalidated (the user
     * re-enrolled their fingerprint, did a factory reset, etc.). Falls back to PIN-only so the
     * user can still unlock; they can re-enable biometric from Settings afterwards.
     */
    fun disableBiometricSilently() {
        viewModelScope.launch { appLock.disableBiometric() }
    }
}

@Composable
fun LockScreen(
    onUnlocked: () -> Unit,
    viewModel: LockViewModel = hiltViewModel(),
) {
    val state by viewModel.appLock.state.collectAsStateWithLifecycle()
    val lockMode by viewModel.lockMode.collectAsStateWithLifecycle()
    val wrongCode by viewModel.wrongCode.collectAsStateWithLifecycle()
    var pin by remember { mutableStateOf("") }
    var fallbackToPin by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Un écran de verrou ne doit JAMAIS être « dépassé » par le bouton Retour : sans ce handler,
    // Retour déléguait au NavHost, qui dépilait l'écran Lock et révélait la liste verrouillée
    // derrière (fuite). Le Retour est donc capté ici, et ne franchit jamais le verrou.
    //
    // v1.26.0 — il ne se contente plus de l'absorber : il **met l'application en arrière-plan**,
    // exactement comme le bouton Accueil. Un Retour qui ne produit rigoureusement rien laisse
    // croire à un écran figé — constaté à l'usage, et c'est d'autant plus déroutant que l'écran
    // apparaît justement quand on ne s'y attend pas.
    //
    // Le commentaire d'origine écartait `moveTaskToBack` en le jugeant « superflu » (la racine
    // non-dépilable étant garantie par `startDestination = Conversations`). C'était vrai du point
    // de vue de la page blanche ; ça ne réglait pas la question de l'ergonomie.
    //
    // Aucune concession de sécurité : la tâche part en arrière-plan, `_state` reste `Locked`, et
    // l'écran de verrouillage est là au retour. C'est ce que fait toute application verrouillée.
    androidx.activity.compose.BackHandler(enabled = true) {
        context.findFragmentActivity()?.moveTaskToBack(true)
    }

    LaunchedEffect(state) {
        // R2 fix: also dismiss when the lock is disabled — happens on fresh install where
        // resolveInitialState() flips Locked→Disabled after we've already composed.
        if (state is AppLockManager.LockState.Unlocked ||
            state is AppLockManager.LockState.PanicDecoy ||
            state is AppLockManager.LockState.Disabled
        ) {
            onUnlocked()
        }
    }

    // Auto-trigger biometric prompt when:
    //  - the user has BIOMETRIC mode armed,
    //  - the session is still Locked (not LockedOut — biometric must NOT bypass the cool-down),
    //  - the user has not yet tapped "Use PIN" in the prompt.
    val lifecycleOwner = LocalLifecycleOwner.current
    // v1.25.3 — garde anti-empilement. Préparer la clé Keystore puis attendre `withResumed`
    // introduit une fenêtre asynchrone entre le déclenchement de cet effet et l'affichage du
    // prompt. Comme il se relance sur chaque changement de `state`, deux prompts pouvaient s'y
    // chevaucher — et [VaultScreen] documente que certains OEM Samsung/Xiaomi les empilent au
    // lieu d'ignorer le second. Le drapeau survit aux relances (`remember` sans clé) et n'est
    // rendu qu'en cas d'échec, quand un nouvel essai est légitime.
    var promptInFlight by remember { mutableStateOf(false) }
    LaunchedEffect(state, lockMode, fallbackToPin) {
        if (lockMode != LockMode.BIOMETRIC) return@LaunchedEffect
        if (state !is AppLockManager.LockState.Locked) return@LaunchedEffect
        if (fallbackToPin) return@LaunchedEffect
        if (promptInFlight) return@LaunchedEffect
        val activity = context.findFragmentActivity() ?: return@LaunchedEffect

        // v1.25.3 (audit H2) — Classe 3 exigée, via l'unique politique [StrongBiometrics].
        val status = StrongBiometrics.status(context)
        if (status != BiometricManager.BIOMETRIC_SUCCESS) {
            // No usable biometric on the device — auto-fall back to PIN so we never trap the user.
            // Et si l'indispo est définitive (pas de capteur Classe 3, plus aucune empreinte), on
            // désarme le réglage : sinon Réglages continuerait d'afficher « Biométrie » pour un
            // déverrouillage qui ne se déclencherait plus jamais.
            if (StrongBiometrics.isPermanentlyUnavailable(status)) viewModel.disableBiometricSilently()
            fallbackToPin = true
            return@LaunchedEffect
        }

        // v1.25.3 — clé Keystore adossée à la biométrie. Préparée AVANT le prompt, hors du
        // thread principal (la génération touche le matériel sécurisé).
        val prepared = viewModel.prepareBiometricGate()
        val gateCipher = when (prepared) {
            is com.filestech.sms.ui.security.BiometricGate.Prepared.Ready -> prepared.cipher
            com.filestech.sms.ui.security.BiometricGate.Prepared.Invalidated -> {
                // Empreintes ré-inscrites : la clé ne s'ouvrira plus jamais. Désarmer est
                // impératif — la laisser armée enfermerait l'utilisateur dehors.
                viewModel.disableBiometricSilently()
                fallbackToPin = true
                return@LaunchedEffect
            }
            com.filestech.sms.ui.security.BiometricGate.Prepared.Unavailable -> {
                // Keystore capricieux : on retombe sur le PIN sans désarmer le réglage, la panne
                // pouvant être passagère.
                fallbackToPin = true
                return@LaunchedEffect
            }
        }

        val challenge = viewModel.beginBiometricChallenge()
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    // La preuve est ici : sans `doFinal` accepté par le Keystore, le succès
                    // annoncé n'est adossé à aucune clé et on refuse le déverrouillage.
                    promptInFlight = false
                    if (viewModel.confirmBiometricGate(result.cryptoObject?.cipher)) {
                        viewModel.markBiometricUnlocked(challenge)
                    } else {
                        fallbackToPin = true
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    promptInFlight = false
                    Timber.i("BiometricPrompt error %d: %s", errorCode, errString)
                    when (errorCode) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_CANCELED -> fallbackToPin = true
                        BiometricPrompt.ERROR_HW_NOT_PRESENT,
                        BiometricPrompt.ERROR_NO_BIOMETRICS -> {
                            // The user removed all enrolled biometrics — disable to keep Settings honest.
                            viewModel.disableBiometricSilently()
                            fallbackToPin = true
                        }
                        // v1.25.3 (audit H2) — `ERROR_HW_UNAVAILABLE` est **transitoire** (« try
                        // again later » : capteur occupé par une autre app, throttling thermique).
                        // Il était traité comme une disparition définitive du capteur et désarmait
                        // le réglage : un simple raté faisait perdre silencieusement le
                        // déverrouillage biométrique. Même distinction que
                        // [StrongBiometrics.isPermanentlyUnavailable] applique en amont — les deux
                        // extrémités doivent dire la même chose.
                        BiometricPrompt.ERROR_HW_UNAVAILABLE,
                        BiometricPrompt.ERROR_LOCKOUT,
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> fallbackToPin = true
                        else -> fallbackToPin = true
                    }
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(R.string.lock_biometric_title))
            .setSubtitle(context.getString(R.string.lock_biometric_subtitle))
            .setNegativeButtonText(context.getString(R.string.lock_use_pin))
            .setAllowedAuthenticators(StrongBiometrics.AUTHENTICATORS)
            .setConfirmationRequired(false)
            .build()
        // v1.25.3 — `withResumed` : passé `onSaveInstanceState`, `authenticate()` renonce.
        // androidx.biometric 1.1.0 teste `FragmentManager.isStateSaved()`, journalise « Unable to
        // start authentication. Called after onSaveInstanceState(). » et retourne — sans lever, et
        // surtout **sans appeler aucun callback** : le prompt ne s'affiche jamais et rien ne
        // signale l'abandon. Ce LaunchedEffect vit dans la composition, qui survit à un passage en
        // arrière-plan ; sans cette attente, un prompt préparé pendant que l'écran part en
        // arrière-plan se perdait ainsi. Attendre le premier plan plutôt que renoncer : un garde
        // `if (!isResumed) return` annulerait le prompt au lancement, quand l'activité n'est pas
        // encore RESUMED.
        //
        // v1.25.4 — `try/finally` : `promptInFlight` était levé AVANT une suspension, et seuls les
        // callbacks le rabaissaient. Une annulation du LaunchedEffect pendant l'attente
        // n'en déclenche aucun, et le drapeau — un `remember` sans clé — survit à la relance de
        // l'effet : le garde d'entrée bloquait alors définitivement toute nouvelle tentative, ne
        // laissant plus que le repli PIN. On ne relâche que si `authenticate()` n'a pas été
        // atteint ; sinon les callbacks restent seuls maîtres du drapeau.
        promptInFlight = true
        var handedOff = false
        try {
            lifecycleOwner.lifecycle.withResumed {
                prompt.authenticate(info, BiometricPrompt.CryptoObject(gateCipher))
                handedOff = true
            }
        } finally {
            if (!handedOff) promptInFlight = false
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(24.dp))
            Text(text = stringResource(R.string.lock_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.size(16.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = {
                    if (it.length <= 12 && it.all { c -> c.isDigit() }) {
                        pin = it
                        // Le message disparait des la premiere frappe : le garder affiche pendant
                        // une nouvelle saisie donnerait l'impression que le nouvel essai a echoue
                        // avant meme d'avoir ete soumis.
                        if (wrongCode) viewModel.clearWrongCode()
                    }
                },
                label = { Text(stringResource(R.string.lock_pin_hint)) },
                isError = wrongCode,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
            )
            // v1.26.0 — masque pendant `LockedOut` : le compte a rebours plus bas dit deja ce
            // qu'il faut, et empiler les deux messages rouges brouillerait l'information utile.
            if (wrongCode && state !is AppLockManager.LockState.LockedOut) {
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.lock_wrong_code),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.size(16.dp))
            val isLockedOut = state is AppLockManager.LockState.LockedOut
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { viewModel.attempt(pin.toCharArray()); pin = "" },
                    // Audit I1 (v1.14.8) — bouton désactivé pendant LockedOut : avant, l'user
                    // pouvait tapper "Continuer" sans feedback visible. attemptUnlock re-check
                    // côté manager et n'incrémente pas l'échec, mais l'UX paraissait cassée.
                    enabled = pin.isNotEmpty() && !isLockedOut,
                ) { Text(stringResource(R.string.action_continue)) }
                // Re-show the biometric prompt manually if the user dismissed it but still wants
                // to try the finger (only relevant in BIOMETRIC mode).
                if (lockMode == LockMode.BIOMETRIC && fallbackToPin) {
                    Spacer(Modifier.size(8.dp))
                    IconButton(onClick = { fallbackToPin = false }) {
                        Icon(
                            imageVector = Icons.Outlined.Fingerprint,
                            contentDescription = stringResource(R.string.lock_biometric_title),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (isLockedOut) {
                // Audit R5 (v1.14.8) — countdown TICK 1s. Avant : `remaining` calculé une seule
                // fois à la recomposition (déclenchée seulement par changement de `state`), donc
                // le chiffre affiché était figé. L'user voyait "60 secondes" sans décrément →
                // attente faussement longue. Ce LaunchedEffect ne tourne QUE pendant LockedOut.
                val until = (state as AppLockManager.LockState.LockedOut).until
                var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(until) {
                    // v1.26.1 (audit M1) — le minuteur est un DÉCLENCHEUR DE SONDAGE, pas le
                    // décideur. La boucle ne sort donc que lorsque le manager confirme que le
                    // blocage est réellement levé.
                    //
                    // Elle sortait sur `nowMs >= until` puis appelait une seule fois
                    // `onLockoutCountdownFinished()`. Or ce dernier peut légitimement rendre
                    // `false` — c'est l'instantané persistant qui tranche, en croisant horloge
                    // murale et horloge monotone. Dans ce cas l'état restait `LockedOut`, la
                    // boucle était finie, et RIEN ne la relançait : `until` ne changeait pas,
                    // donc la clé de l'effet non plus. Compte à rebours à zéro, bouton mort
                    // jusqu'à ce que l'utilisateur tue le processus — le symptôme même corrigé
                    // en v1.26.0, déplacé d'un cran.
                    while (true) {
                        nowMs = System.currentTimeMillis()
                        if (nowMs >= until) {
                            if (viewModel.onLockoutCountdownFinished()) return@LaunchedEffect
                            // Refusé : on re-sonde à la seconde plutôt que d'abandonner.
                        }
                        delay(1000L)
                    }
                }
                val remaining by remember(until) {
                    derivedStateOf { ((until - nowMs) / 1000L).coerceAtLeast(0L).toInt() }
                }
                Spacer(Modifier.size(12.dp))
                Text(
                    text = stringResource(R.string.lock_lockout_message, remaining),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Walks the [ContextWrapper] chain to find the hosting [FragmentActivity]. Required by
 * [BiometricPrompt], which attaches a transient fragment to the Activity's lifecycle. Compose's
 * `LocalContext` resolves to the Activity in the standard setup, but the helper makes the
 * fallback explicit so a custom wrapper (e.g. `LocaleAwareContext`) is also unwrapped cleanly.
 */
private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    is Activity -> null
    else -> null
}
