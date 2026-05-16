package com.filestech.sms

import android.content.Intent
import android.net.Uri
import android.os.Build
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
import com.filestech.sms.system.share.IncomingShareHolder
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
    @Inject lateinit var incomingShare: IncomingShareHolder

    private val initialSettings = MutableStateFlow<AppSettings?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { initialSettings.value == null }
        // Hilt injects @Inject lateinit properties inside super.onCreate via the @AndroidEntryPoint
        // bytecode transform. We must call super FIRST before touching `settings` or `appLock`.
        super.onCreate(savedInstanceState)

        // v1.3.3 bug #4 — partage entrant ACTION_SEND / ACTION_SEND_MULTIPLE depuis le
        // chooser système (Galerie, navigateur, fichiers…). On parse l'intent et on stocke
        // dans le holder ; AppRoot l'observe pour basculer en mode "pick to share".
        handleSharedIntent(intent)

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

    /**
     * v1.3.3 bug #4 — l'activité est `launchMode="singleTask"`, donc un partage
     * entrant alors qu'une instance existe déjà arrive via [onNewIntent] et PAS
     * [onCreate]. On câble les deux pour ne jamais rater un share.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
    }

    /**
     * v1.3.3 bug #4 — parse [Intent.ACTION_SEND] / [Intent.ACTION_SEND_MULTIPLE] et
     * pose le résultat dans [IncomingShareHolder]. L'UI (AppRoot) prend le relais pour
     * naviguer vers le picker de conversation. No-op pour tout autre type d'intent.
     *
     * Sécurité :
     *   - Lecture d'URIs uniquement (jamais d'exec, jamais d'écriture en aveugle).
     *   - Le `type` (MIME) est purement indicatif : la dispatch finale revalide
     *     l'extension / le contenu via le pipeline existant SendMediaMmsUseCase
     *     (qui caps déjà à 280 KB et inspecte le header image — audit F5 PDF Tech
     *     style).
     */
    private fun handleSharedIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.extraStream()
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (uri != null || !text.isNullOrBlank()) {
                    incomingShare.set(
                        IncomingShareHolder.Pending(
                            uris = listOfNotNull(uri),
                            mimeType = intent.type,
                            text = text,
                        ),
                    )
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.extraStreamList()
                if (uris.isNotEmpty()) {
                    incomingShare.set(
                        IncomingShareHolder.Pending(
                            uris = uris,
                            mimeType = intent.type,
                            text = intent.getStringExtra(Intent.EXTRA_TEXT),
                        ),
                    )
                }
            }
            else -> {
                // v1.3.3 G2 audit fix — l'app a été relancée via un intent NON-SEND
                // (icône launcher, tap notif `OPEN_CONVERSATION`, deep-link sms:…).
                // Si un partage attend depuis une session précédente, on l'efface :
                // l'intention de l'utilisateur a changé, pas question d'attacher
                // une PJ oubliée à la conversation qu'il vient d'ouvrir.
                incomingShare.clear()
            }
        }
    }

    /** v1.3.3 — récupère `EXTRA_STREAM` avec API moderne (Android 13+) + legacy fallback. */
    @Suppress("DEPRECATION")
    private fun Intent.extraStream(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_STREAM)
        }

    /** v1.3.3 — récupère la liste d'URIs pour ACTION_SEND_MULTIPLE. */
    @Suppress("DEPRECATION")
    private fun Intent.extraStreamList(): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
}
