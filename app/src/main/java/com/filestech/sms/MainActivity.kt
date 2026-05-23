package com.filestech.sms

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import com.filestech.sms.system.notifications.SafetyCallIntentToken
import com.filestech.sms.system.notifications.SafetyCallWarningNotifier
import com.filestech.sms.system.notifications.IncomingMessageNotifier
import com.filestech.sms.system.notifications.PendingNavHolder
import com.filestech.sms.system.share.IncomingShareHolder
import com.filestech.sms.ui.AppRoot
import com.filestech.sms.ui.theme.SmsTechTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
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
    @Inject lateinit var safetyCallIntentToken: SafetyCallIntentToken

    /**
     * v1.14.7 — safety net : déclenche un delta-sync à chaque retour foreground.
     * Pattern single-flight + Mutex côté manager → re-tap rapides sont free.
     * Couvre les cas où Samsung Freecess ou un OEM agressif a gelé le worker
     * pendant que l'app était en background, ou où le ContentObserver système
     * a manqué une émission.
     */
    @Inject lateinit var telephonySyncManager: com.filestech.sms.data.sync.TelephonySyncManager

    /**
     * v1.14.7 audit P1 — anti-spam `enqueueOneShot` WorkManager. Sans cooldown, un
     * user qui switche entre apps rapidement enqueue un OneShot à chaque onResume.
     * KEEP policy évite la dup d'exécution, mais chaque enqueue = 1 write WorkManager
     * SQLite (interne) → peut déclencher Samsung Freecess throttling. `requestSync()`
     * via Mutex absorbe déjà le hammering ; WorkManager est notre filet "process killed",
     * un enqueue toutes les 30s suffit largement (le worker tourne 12h périodique).
     */
    @Volatile private var lastOneShotEnqueueElapsed: Long = 0L
    private val oneShotEnqueueCooldownMs: Long = 30_000L

    /**
     * v1.8.0 (bug 4 fix) — holder partagé pour le `conversationId` cliqué via
     * notification. Posé dans [handleSharedIntent] quand l'action vaut
     * [IncomingMessageNotifier.ACTION_OPEN_CONVERSATION], consommé dans
     * [com.filestech.sms.ui.AppRoot] par un `LaunchedEffect` après que le
     * `NavController` Compose est instancié et les guards (lock screen /
     * panic-decoy / déjà sur ce thread) sont validés.
     */
    @Inject lateinit var pendingNav: PendingNavHolder

    private val initialSettings = MutableStateFlow<AppSettings?>(null)

    /**
     * v1.3.10 — launcher pour demande EXPLICITE des permissions runtime critiques au
     * cold-start. Contrat Android : quand une app devient `RoleManager.ROLE_SMS`
     * holder, les permissions `READ_SMS`/`RECEIVE_SMS`/`RECEIVE_MMS`/`RECEIVE_WAP_PUSH`
     * doivent être grantées automatiquement via `GRANTED_BY_ROLE`. **BUG OEM observé**
     * sur Samsung Galaxy S9 Android 10 One UI : le grant automatique ne s'exécute pas,
     * les permissions restent `restricted=true`, et l'utilisateur ne reçoit aucun SMS /
     * MMS sans intervention manuelle (Paramètres → Apps → SMS Tech → Autorisations).
     * Pour rendre l'app robuste sans assumer ce contrat OEM, on demande nous-mêmes les
     * permissions runtime manquantes au premier launch (et à chaque cold-start si user
     * a refusé, jusqu'à grant explicite ou refus "Ne plus demander" du système).
     */
    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grantResults ->
        val denied = grantResults.filter { !it.value }.keys
        if (denied.isNotEmpty()) {
            Timber.w(
                "MainActivity: %d critical permissions still denied after user prompt: %s",
                denied.size,
                denied.joinToString(),
            )
            // Pas d'action forcée — l'utilisateur peut toujours les activer plus tard
            // via Paramètres système. L'app reste fonctionnelle pour les paths qui ne
            // dépendent pas des perms denied (ex: SMS texte si seul RECEIVE_MMS est
            // denied).
        }
    }

    /**
     * v1.3.10 — liste des permissions runtime "dangerous" indispensables au fonctionnement
     * de SMS Tech en tant qu'app SMS par défaut. Calculée dynamiquement à chaque check
     * pour inclure `POST_NOTIFICATIONS` uniquement sur Android 13+ (API 33+) où elle a
     * été introduite.
     */
    private val criticalSmsPermissions: Array<String>
        get() = mutableListOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.RECEIVE_MMS,
            Manifest.permission.RECEIVE_WAP_PUSH,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.RECORD_AUDIO,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    /**
     * Demande les permissions runtime manquantes parmi [criticalSmsPermissions]. No-op
     * si toutes déjà grantées (cas Pixel + Samsung One UI ≥ 11 où le grant automatique
     * via ROLE_SMS fonctionne). Idempotent — Android dédoublonne les requests successifs.
     */
    private fun requestMissingCriticalPermissionsIfAny() {
        val missing = criticalSmsPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            Timber.i(
                "MainActivity: requesting %d missing critical permissions at cold-start (likely Samsung One UI Android 10 GRANTED_BY_ROLE bug)",
                missing.size,
            )
            permissionsLauncher.launch(missing.toTypedArray())
        }
    }

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

        // v1.3.10 — fail-safe contre le bug Samsung One UI Android 10 où GRANTED_BY_ROLE
        // ne grant pas les permissions runtime critiques (RECEIVE_SMS, RECEIVE_MMS,
        // RECEIVE_WAP_PUSH, etc.) malgré que SMS Tech soit role holder. Sans ce check,
        // l'utilisateur croit que SMS Tech est correctement configurée (badge "app par
        // défaut" affiché dans Paramètres système) mais ne reçoit aucun SMS / MMS. On
        // demande explicitement les permissions manquantes au cold-start ; les ROMs où
        // le grant automatique a fonctionné voient toutes les perms déjà à
        // PERMISSION_GRANTED → `requestMissingCriticalPermissionsIfAny` est no-op
        // (Android dédoublonne, aucun popup affiché).
        requestMissingCriticalPermissionsIfAny()

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
                    // v1.4.1 — the v1.3.10 [OemKeepAliveOnboarding] auto-popup was removed
                    // here because it surprised users on Xiaomi / Redmi who tapped "Activer"
                    // without realising it would create a permanent system notification
                    // (a hard requirement for Android's foreground-service contract). The
                    // "Mode résistant" toggle remains accessible via Réglages → Avancé for
                    // power users who explicitly want the trade-off.
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
     * v1.9.0 — Safety call : reset du timer à chaque resume de l'app.
     * Le contract du Safety call est "si tu n'ouvres pas SMS Tech pendant
     * X heures, j'envoie un SMS à tes proches" — donc chaque ouverture
     * d'app est la preuve d'activité attendue. On délègue à un coroutine
     * lifecycleScope pour ne pas bloquer le main thread (DataStore async).
     *
     * **Audit fix SEC-9** : reset uniquement si l'app est réellement
     * déverrouillée par l'utilisateur ([AppLockManager.LockState.Unlocked]
     * ou [AppLockManager.LockState.Disabled] = pas de lock configuré). Ne
     * PAS reset si :
     *  - [AppLockManager.LockState.Locked] : l'attaquant a juste ouvert
     *    l'app sans connaître le PIN, ne doit pas pouvoir neutraliser le
     *    deadman.
     *  - [AppLockManager.LockState.PanicDecoy] : l'user est sous contrainte
     *    et a saisi le PIN panic ; le deadman doit continuer à courir pour
     *    que les contacts d'urgence soient alertés.
     *
     * Lecture conditionnelle : on ne fait l'update que si `enabled = true`.
     * Sinon, c'est une écriture DataStore inutile à chaque resume (la
     * majorité des users n'utilisera pas Safety call). Coût quand activé :
     * 1 écriture DataStore par resume = ~5 ms, négligeable.
     */
    override fun onResume() {
        super.onResume()
        // v1.14.7 — safety net : re-déclenche un delta-sync à chaque retour
        // foreground. Idempotent (single-flight Mutex). Couvre Samsung Freecess
        // qui gèle le worker en background + le ContentObserver système qui peut
        // manquer une émission après un sleep long. Pas de garde lockState ici
        // car la sync écrit en Room derrière l'AppLockManager, indépendamment
        // de l'écran de verrouillage UI.
        runCatching { telephonySyncManager.requestSync(reason = "MainActivity.onResume") }
            .onFailure { Timber.w(it, "onResume sync trigger failed") }
        // Belt-and-braces : double-track via WorkManager au cas où le manager
        // serait dans un état inattendu (started flag bloqué, mutex deadlock).
        // v1.14.7 audit P1 fix : throttle à 1 enqueue / 30s mono pour éviter de spammer
        // WorkManager si l'user oscille entre apps rapidement. `requestSync()` ci-dessus
        // gère déjà le path rapide via Mutex ; le worker est pour le path "process killé".
        val nowMono = android.os.SystemClock.elapsedRealtime()
        if (nowMono - lastOneShotEnqueueElapsed >= oneShotEnqueueCooldownMs) {
            lastOneShotEnqueueElapsed = nowMono
            runCatching {
                com.filestech.sms.system.scheduler.TelephonySyncWorker.enqueueOneShot(this)
            }.onFailure { Timber.w(it, "onResume enqueueOneShot failed") }
        }
        lifecycleScope.launch {
            runCatching {
                val lockState = appLock.state.value
                val isRealOpen = lockState is AppLockManager.LockState.Unlocked ||
                    lockState is AppLockManager.LockState.Disabled
                if (!isRealOpen) {
                    Timber.d("MainActivity: skipping Safety call reset (lockState=%s)", lockState)
                    return@runCatching
                }
                val current = settings.state.value.security.safetyCall
                if (current.enabled) {
                    settings.update { s ->
                        s.copy(
                            security = s.security.copy(
                                safetyCall = s.security.safetyCall.copy(
                                    lastActivityAt = System.currentTimeMillis(),
                                    // v1.10.0 SEC-11 — couple mono+wall à chaque reset.
                                    monotonicLastActivityAt = SystemClock.elapsedRealtime(),
                                ),
                            ),
                        )
                    }
                    Timber.d("MainActivity: Safety call timer reset on resume")
                }
            }
        }
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
            SafetyCallWarningNotifier.ACTION_SAFETY_CALL_RESET -> {
                // v1.9.0 — tap sur la notification "Confirme que tu vas bien" :
                // reset immédiat du timer + dismiss de la notif. On délègue à
                // un usecase coroutine via lifecycleScope pour ne pas bloquer
                // onNewIntent / onCreate.
                //
                // **Audit fix SEC-10** : valide le nonce mono-usage avant
                // de reset. MainActivity est `exported="true"` (rôle SMS)
                // donc une app tierce pourrait forger ACTION_SAFETY_CALL_RESET ;
                // sans le bon token (re-genéré à chaque pose de notif et
                // connu uniquement du process SMS Tech), l'intent est ignoré.
                val token = intent.getLongExtra(
                    SafetyCallWarningNotifier.EXTRA_RESET_TOKEN, 0L,
                )
                if (!safetyCallIntentToken.consume(token)) {
                    Timber.w("MainActivity: rejecting SAFETY_CALL_RESET — invalid/missing token")
                    incomingShare.clear()
                    return
                }
                lifecycleScope.launch {
                    runCatching {
                        settings.update { s ->
                            s.copy(
                                security = s.security.copy(
                                    safetyCall = s.security.safetyCall.copy(
                                        lastActivityAt = System.currentTimeMillis(),
                                        // v1.10.0 SEC-11 — couple mono+wall.
                                        monotonicLastActivityAt = SystemClock.elapsedRealtime(),
                                    ),
                                ),
                            )
                        }
                        Timber.i("MainActivity: deadman timer reset via warning notif tap")
                    }
                }
                incomingShare.clear()
            }
            com.filestech.sms.system.receiver.EmergencyShortcutReceiver.ACTION_OPEN_EMERGENCY -> {
                // v1.14.1 — l'utilisateur a tapé le corps de la notification
                // persistante du raccourci urgence (PendingIntent setContentIntent
                // posé par EmergencyShortcutNotifier). On marque pending nav
                // openEmergency=true ; AppRoot consommera dans un LaunchedEffect
                // après hydratation du NavController + guards (lock actif → pas
                // de pop, panic-decoy → pas de pop).
                incomingShare.clear()
                pendingNav.set(PendingNavHolder.Pending(openEmergency = true))
            }
            IncomingMessageNotifier.ACTION_OPEN_CONVERSATION -> {
                // v1.8.0 (bug 4 fix) — l'utilisateur a tapé une notif de message
                // entrant. On extrait le `conversationId` mis en extra par
                // [IncomingMessageNotifier] et on le dépose dans [pendingNav].
                // [AppRoot] consommera ce holder dans un `LaunchedEffect` après
                // que le `NavController` Compose est instancié, avec les guards
                // habituels (lock actif, panic-decoy, déjà sur ce thread).
                //
                // **Important** : on `clear()` aussi le `incomingShare` ici —
                // un tap notif est une intention de navigation pure, pas un
                // partage. Si un share traînait dans le holder depuis une
                // session précédente, on ne veut surtout pas qu'il s'attache
                // à la conversation qu'on vient d'ouvrir.
                incomingShare.clear()
                val conversationId =
                    intent.getLongExtra(IncomingMessageNotifier.EXTRA_CONVERSATION_ID, -1L)
                if (conversationId > 0L) {
                    pendingNav.set(PendingNavHolder.Pending(conversationId = conversationId))
                } else {
                    Timber.w(
                        "MainActivity: OPEN_CONVERSATION intent missing valid conversationId (got %d)",
                        conversationId,
                    )
                }
            }
            else -> {
                // v1.3.3 G2 audit fix — l'app a été relancée via un intent NON-SEND
                // (icône launcher, deep-link sms:…). Si un partage attend depuis
                // une session précédente, on l'efface : l'intention de l'utilisateur
                // a changé, pas question d'attacher une PJ oubliée à la conversation
                // qu'il vient d'ouvrir.
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
