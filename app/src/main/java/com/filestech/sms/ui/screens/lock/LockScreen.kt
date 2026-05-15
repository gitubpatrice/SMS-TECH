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
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.filestech.sms.R
import com.filestech.sms.data.local.datastore.LockMode
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.security.AppLockManager
import dagger.hilt.android.lifecycle.HiltViewModel
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
) : ViewModel() {

    /** Current lock mode — drives whether the biometric prompt fires at screen entry. */
    val lockMode: StateFlow<LockMode> = settings.flow
        .map { it.security.lockMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), LockMode.OFF)

    fun attempt(pin: CharArray) {
        viewModelScope.launch { appLock.attemptUnlock(pin) }
    }

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
    var pin by remember { mutableStateOf("") }
    var fallbackToPin by remember { mutableStateOf(false) }
    val context = LocalContext.current

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
    LaunchedEffect(state, lockMode, fallbackToPin) {
        if (lockMode != LockMode.BIOMETRIC) return@LaunchedEffect
        if (state !is AppLockManager.LockState.Locked) return@LaunchedEffect
        if (fallbackToPin) return@LaunchedEffect
        val activity = context.findFragmentActivity() ?: return@LaunchedEffect

        val biometricManager = BiometricManager.from(context)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK
        if (biometricManager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            // No usable biometric on the device — auto-fall back to PIN so we never trap the user.
            fallbackToPin = true
            return@LaunchedEffect
        }

        val challenge = viewModel.beginBiometricChallenge()
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    viewModel.markBiometricUnlocked(challenge)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Timber.i("BiometricPrompt error %d: %s", errorCode, errString)
                    when (errorCode) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_CANCELED -> fallbackToPin = true
                        BiometricPrompt.ERROR_HW_NOT_PRESENT,
                        BiometricPrompt.ERROR_HW_UNAVAILABLE,
                        BiometricPrompt.ERROR_NO_BIOMETRICS -> {
                            // The user removed all enrolled biometrics — disable to keep Settings honest.
                            viewModel.disableBiometricSilently()
                            fallbackToPin = true
                        }
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
            .setAllowedAuthenticators(authenticators)
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info)
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
                onValueChange = { if (it.length <= 12 && it.all { c -> c.isDigit() }) pin = it },
                label = { Text(stringResource(R.string.lock_pin_hint)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
            )
            Spacer(Modifier.size(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { viewModel.attempt(pin.toCharArray()); pin = "" },
                    enabled = pin.isNotEmpty(),
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
            if (state is AppLockManager.LockState.LockedOut) {
                val remaining = (((state as AppLockManager.LockState.LockedOut).until - System.currentTimeMillis()) / 1000)
                    .coerceAtLeast(0)
                Spacer(Modifier.size(12.dp))
                Text(
                    text = stringResource(R.string.lock_lockout_message, remaining.toInt()),
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
