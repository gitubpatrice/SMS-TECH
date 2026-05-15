package com.filestech.sms

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.filestech.sms.data.local.datastore.AppSettings
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.security.AppLockManager
import com.filestech.sms.ui.AppRoot
import com.filestech.sms.ui.theme.SmsTechTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
/**
 * Hosts the single Compose tree. Extends [FragmentActivity] (vs. the bare `ComponentActivity`
 * used until v1.1.1) so the `androidx.biometric` `BiometricPrompt` can attach its internal
 * `Fragment` — required to drive the biometric flow from [LockScreen]. `FragmentActivity` is a
 * strict super-set of `ComponentActivity` for Compose purposes (it still extends
 * `androidx.activity.ComponentActivity`), so this swap is invisible to the rest of the app.
 */
class MainActivity : FragmentActivity() {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var appLock: AppLockManager

    private val initialSettings = MutableStateFlow<AppSettings?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { initialSettings.value == null }
        // Hilt injects @Inject lateinit properties inside super.onCreate via the @AndroidEntryPoint
        // bytecode transform. We must call super FIRST before touching `settings` or `appLock`.
        super.onCreate(savedInstanceState)

        // Audit P-P0-5: the previous `runBlocking(IO) { settings.flow.first() }` blocked the main
        // thread 50-200 ms on DataStore to know whether to apply FLAG_SECURE. We now apply it
        // **inconditionally as a safe default** — privacy-preserving by construction — and only
        // CLEAR the flag once the async settings read confirms the user has opted out. Net
        // effect: the user is never exposed in Recents during cold-start, and the main thread
        // is freed.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        enableEdgeToEdge()

        // Resolve the initial AppSettings asynchronously. The splash screen stays visible until
        // `initialSettings` flips to a non-null value (see `setKeepOnScreenCondition` above), so
        // there is no visual flash to default theme / accent during the read.
        lifecycleScope.launch {
            initialSettings.value = settings.flow.first()
        }

        // The actual lock-state resolution still happens lazily — receivers / services call
        // `appLock.ensureResolved()` themselves; nothing here needs to block on it.
        lifecycleScope.launch { appLock.ensureResolved() }

        // Observe later toggles of FLAG_SECURE. distinctUntilChanged avoids the no-op addFlags /
        // clearFlags churn on every settings emission (audit P8). Note this also handles the
        // initial pass: when the first emission arrives with `flagSecure = false`, this clears
        // the safe-default we applied above.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settings.flow
                    .map { it.security.flagSecure }
                    .distinctUntilChanged()
                    .collect { applyFlagSecure(it) }
            }
        }

        setContent {
            // While the async settings read is in flight, `initialSettings.value` is null and
            // the splash screen masks the Compose tree. We render the defaults under the splash
            // so the first composition is ready the moment the splash clears.
            val seed = initialSettings.collectAsStateWithLifecycle().value ?: AppSettings()
            val current by settings.flow.collectAsStateWithLifecycle(initialValue = seed)
            SmsTechTheme(appearance = current.appearance) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }

    private fun applyFlagSecure(enabled: Boolean) {
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
