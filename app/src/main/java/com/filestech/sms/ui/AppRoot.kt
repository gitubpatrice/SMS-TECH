package com.filestech.sms.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.filestech.sms.security.AppLockManager
import com.filestech.sms.ui.navigation.About
import com.filestech.sms.ui.navigation.Backup
import com.filestech.sms.ui.navigation.Blocked
import com.filestech.sms.ui.navigation.Compose
import com.filestech.sms.ui.navigation.Conversations
import com.filestech.sms.ui.navigation.Emergency
import com.filestech.sms.ui.navigation.EmergencySetup
import com.filestech.sms.ui.navigation.SafetyCallSetup
import com.filestech.sms.ui.navigation.Lock
import com.filestech.sms.ui.navigation.Migration
import com.filestech.sms.ui.navigation.Settings
import com.filestech.sms.ui.navigation.ScheduledMessages
import com.filestech.sms.ui.navigation.Splash
import com.filestech.sms.ui.navigation.Thread
import com.filestech.sms.ui.navigation.Vault
import com.filestech.sms.ui.screens.about.AboutScreen
import com.filestech.sms.ui.screens.backup.BackupScreen
import com.filestech.sms.ui.screens.blocked.BlockedNumbersScreen
import com.filestech.sms.ui.screens.compose.ComposeScreen
import com.filestech.sms.ui.screens.conversations.ConversationsScreen
import com.filestech.sms.ui.screens.lock.LockScreen
import com.filestech.sms.ui.screens.migration.MigrationScreen
import com.filestech.sms.ui.screens.settings.SettingsScreen
import com.filestech.sms.ui.screens.splash.SplashScreen
import com.filestech.sms.ui.screens.thread.ThreadScreen
import com.filestech.sms.ui.screens.vault.VaultScreen

@androidx.compose.runtime.Composable
fun AppRoot() {
    val rootViewModel: AppRootViewModel = hiltViewModel()

    // v1.24.0 SEC-CRIT — rien ne se compose tant que la base n'est pas prête. `installSplashScreen`
    // ne retarde que le DESSIN, pas la composition : sans cette garde, `ConversationsViewModel` et
    // consorts seraient instanciés sur le main thread et y provisionneraient `AppDatabase`, donc la
    // réparation zéro-clé. Le splash système reste affiché pendant ce temps (même condition).
    val databaseReady by rootViewModel.databaseReady.collectAsStateWithLifecycle()
    val databaseFailure by rootViewModel.databaseFailure.collectAsStateWithLifecycle()
    if (!databaseReady) return
    databaseFailure?.let { cause ->
        // La base est inouvrable : composer le graphe de navigation ferait instancier des
        // ViewModels qui la redemanderaient, donc relèveraient — boucle de crash sans explication.
        com.filestech.sms.ui.screens.recovery.DatabaseRecoveryScreen(cause)
        return
    }

    val appLock: AppLockManager = rootViewModel.appLock
    val incomingShare = rootViewModel.incomingShare
    val pendingNav = rootViewModel.pendingNav
    val nav = rememberNavController()
    val lockState by appLock.state.collectAsStateWithLifecycle()
    // Fail-closed: anything that is NOT an explicit "open" state requires the lock screen.
    // This fixes F1 (cold-start bypass) and the panic-decoy edge case (F28 mitigation: decoy is
    // an explicit unlock state, treated as "open" until we ship a second decoy DB in v1.1).
    val showLock = !appLock.isOpenForUi(lockState)
    // Audit S-P0-1: the panic-decoy session must never reach the Vault route. Combined with the
    // data-layer gate in ConversationRepositoryImpl.observeVault, this gives two independent
    // backstops — navigation refuses to land on the screen, and even if a saved nav state forces
    // it, the screen renders empty.
    val isPanicDecoy = lockState is AppLockManager.LockState.PanicDecoy

    LaunchedEffect(showLock) {
        val isOnLock = nav.currentDestination?.route?.contains("Lock") == true
        // R2 fix: pop the Lock screen when the state flips back to an "open" state — e.g. on a
        // fresh install where _state starts Locked (fail-closed) and resolveInitialState()
        // immediately downgrades to Disabled. Without this branch the user is stranded on
        // LockScreen with no PIN configured.
        if (!showLock && isOnLock) {
            nav.popBackStack(route = Lock, inclusive = true)
        } else if (showLock && !isOnLock) {
            nav.navigate(Lock) {
                popUpTo(Conversations()) { inclusive = false }
            }
        }
    }

    // Panic-decoy nav guard: if we land on the Vault route while in decoy state (saved nav,
    // share-target shortcut, app-link, future deep-link), pop it immediately back to the list.
    // v1.10.0 audit SEC-1 — étendu aux routes Emergency / EmergencySetup : un agresseur
    // en session decoy ne doit même pas SAVOIR que l'app dispose d'un Mode urgence (l'illusion
    // "app SMS ordinaire" doit tenir). Le UseCase a déjà une garde PanicDecoy pour le trigger,
    // mais ce guard navigation empêche d'arriver sur l'écran et de voir le bouton URGENCE.
    LaunchedEffect(isPanicDecoy, nav.currentDestination?.route) {
        if (!isPanicDecoy) return@LaunchedEffect
        val route = nav.currentDestination?.route ?: return@LaunchedEffect
        when {
            route.contains("Vault") ->
                nav.popBackStack(route = Vault, inclusive = true)
            route.contains("EmergencySetup") ->
                nav.popBackStack(route = EmergencySetup, inclusive = true)
            route.contains("Emergency") ->
                nav.popBackStack(route = Emergency, inclusive = true)
        }
    }

    // v1.4.1 — auto-route share-target arrivals to the contact picker. Without this,
    // a `Pending` posted by [MainActivity.handleSharedIntent] silently sat in
    // [IncomingShareHolder] until the user happened to open a thread, which felt
    // like the share had been dropped (especially when the app was already in the
    // foreground on an existing thread — the user just saw their previous
    // conversation and assumed nothing happened).
    //
    // Flow now matches iMessage / Google Messages :
    //   ACTION_SEND → Pending posted → AppRoot navigates to ComposeScreen → user
    //   picks a contact → conversation created → ThreadScreen opens →
    //   `consumeIncomingShareIfAny` stages the attachment into the composer.
    //
    // Guard rails :
    //   - skip if the lock screen is up (user must unlock first; the Pending TTL
    //     of 60 s covers a quick unlock),
    //   - skip if we're already on Compose / Thread (the user is mid-flow),
    //   - skip in panic-decoy state (sharing into the decoy session is forbidden
    //     by design — the user's real conversations are not available there).
    val pendingShare by incomingShare.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pendingShare, showLock, isPanicDecoy) {
        val current = pendingShare ?: return@LaunchedEffect
        if (showLock || isPanicDecoy) return@LaunchedEffect
        if (current.isExpired()) return@LaunchedEffect
        val route = nav.currentDestination?.route
        val alreadyInFlow = route?.contains("Compose") == true ||
            route?.contains("Thread") == true
        if (alreadyInFlow) {
            // v1.4.1 (SEC-03) — privacy fix : when a share arrives while the user is
            // already inside a Thread (or in Compose), the Pending used to linger up to
            // its 60 s TTL. If the user then navigated to ANOTHER thread within that
            // window, `ThreadViewModel.consumeIncomingShareIfAny` would silently stage
            // the shared attachment into that wrong conversation's composer — risking
            // an accidental send to the wrong recipient. Clearing the Pending here is
            // a small UX cost (the user has to share again) for a real privacy guard.
            incomingShare.clear()
            return@LaunchedEffect
        }
        nav.navigate(Compose())
    }

    // v1.8.0 (bug 4 fix) — auto-navigate to Thread on notification tap.
    //
    // `PendingNavHolder` is posted by `MainActivity.handleSharedIntent` whenever
    // the activity is launched/resumed with an `OPEN_CONVERSATION` intent. Before
    // v1.8.0, the action fell into the `else` branch of `handleSharedIntent` and
    // no handler navigated anywhere — the user tapped a notification, the app
    // opened on the conversations list, and the tap felt broken.
    //
    // Guard rails — strictly mirror the `incomingShare` flow above :
    //   - skip if the lock screen is up (user must unlock first; the 30 s TTL
    //     on `PendingNavHolder.Pending` covers a quick biometric unlock),
    //   - skip in panic-decoy state (sharing/navigating into the decoy session
    //     is forbidden by design — the user's real conversations are not
    //     available there, navigating to a real conv id would crash or leak),
    //   - skip if already on the target Thread (avoid double-push that would
    //     create a redundant backstack entry).
    val pendingNavValue by pendingNav.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pendingNavValue, showLock, isPanicDecoy) {
        val current = pendingNavValue ?: return@LaunchedEffect
        if (showLock || isPanicDecoy) return@LaunchedEffect
        if (current.isExpired()) {
            // Holder oublié (user a déverrouillé après >30 s) — on nettoie.
            pendingNav.clear()
            return@LaunchedEffect
        }
        // v1.14.1 — branche openEmergency : tap sur le corps de la notif
        // persistante raccourci urgence → nav vers Emergency. Si on est
        // déjà sur Emergency, consomme sans push.
        if (current.openEmergency) {
            val currentRoute = nav.currentDestination?.route
            if (currentRoute?.contains("Emergency") == true) {
                pendingNav.clear()
                return@LaunchedEffect
            }
            pendingNav.consume() ?: return@LaunchedEffect
            nav.navigate(Emergency)
            return@LaunchedEffect
        }
        // v1.14.8 (bug fix "Message" depuis Phone app) — branche sendToAddress :
        // un deep-link `sms:`/`smsto:`/`mms:`/`mmsto:` a été reçu, MainActivity a
        // posé l'adresse. On résout (find or create conv) en coroutine via
        // [AppRootViewModel.resolveSendToAddress] puis on navigue directement vers
        // le thread cible. Le body éventuel est staged dans incomingShare et sera
        // consommé par ThreadViewModel.consumeIncomingShareIfAny pour pré-remplir
        // le composer.
        val sendToAddress = current.sendToAddress
        if (!sendToAddress.isNullOrBlank()) {
            // Consomme avant le résolveur async pour éviter un double-tap qui
            // re-déclencherait le même pending pendant la coroutine.
            val consumed = pendingNav.consume() ?: return@LaunchedEffect
            rootViewModel.resolveSendToAddress(consumed.sendToAddress.orEmpty()) { resolvedId ->
                if (resolvedId == null || resolvedId <= 0L) {
                    // Fallback : on bascule sur ComposeScreen avec l'adresse pré-remplie.
                    // L'user peut alors valider manuellement (ou ajouter d'autres
                    // destinataires pour un groupe MMS).
                    nav.navigate(Compose(initialAddress = consumed.sendToAddress))
                } else {
                    // Pas de `launchSingleTop` ici non plus : un deep-link `sms:` peut
                    // arriver alors qu'un autre thread est déjà ouvert (cf. note plus
                    // bas sur la comparaison par identité de destination). Ce callback
                    // est single-fire de toute façon — `pendingNav.consume()` a vidé
                    // l'état avant l'appel async, donc aucun double-push possible.
                    nav.navigate(Thread(conversationId = resolvedId))
                }
            }
            return@LaunchedEffect
        }
        // Si on est déjà sur ce thread précis, on consomme sans push (évite
        // doublon backstack). Le check est best-effort : `currentDestination
        // .arguments` peut être null pendant une transition de nav.
        //
        // v1.23.2 — NE PAS remplacer ce check par `launchSingleTop = true`, malgré
        // la ressemblance avec les sites Conversations/Vault. Vérifié dans le bytecode
        // de navigation-runtime 2.9.8 (`NavControllerImpl.launchSingleTopInternal`) :
        // la recherche est un `indexOfLast { it.destination === node }` sur TOUT le
        // backQueue, avec comparaison d'IDENTITÉ sur la `NavDestination` — les
        // ARGUMENTS sont ignorés. Or avec les routes type-safe, `Thread(A)` et
        // `Thread(B)` partagent la même et unique `NavDestination` (un seul
        // `composable<Thread>`). `launchSingleTop` ici écraserait donc le thread
        // courant au lieu d'en empiler un nouveau : depuis une notif pour B alors
        // qu'on lit A, le retour ramènerait à la liste au lieu de A.
        // Règle : `launchSingleTop` sur Thread n'est valide QUE depuis une source
        // dont on sait qu'aucun Thread n'est dans le backstack (liste, Coffre) ;
        // les chemins thread → thread exigent ce check précis sur l'argument.
        val currentRoute = nav.currentDestination?.route
        if (currentRoute?.contains("Thread") == true) {
            val args = nav.currentBackStackEntry?.arguments
            val currentId = args?.getLong("conversationId", -1L) ?: -1L
            if (currentId == current.conversationId) {
                pendingNav.clear()
                return@LaunchedEffect
            }
        }
        // Consomme via `.consume()` qui re-vérifie expiration + clear atomique.
        val consumed = pendingNav.consume() ?: return@LaunchedEffect
        nav.navigate(Thread(conversationId = consumed.conversationId))
    }

    // v1.3.7 — startDestination = Splash. Le SplashScreen se charge lui-même de :
    //   - rediriger immédiatement vers Conversations sans rendu si le flag DataStore
    //     `splashShown` est déjà à `true` (cas n°2+ ouvertures de l'app, donc 99 % du temps) ;
    //   - sinon, jouer l'animation (logo scale+fade + tagline) puis persister le flag et
    //     naviguer vers Conversations.
    // Le `LaunchedEffect(showLock)` au-dessus reste compatible : si un lock est actif au
    // boot et que l'utilisateur n'a jamais vu le splash (cas marginal : restore backup
    // sur fresh install), le Lock se superpose au Splash en backstack et l'utilisateur
    // déverrouille avant de voir le splash — UX dégradée acceptable pour ce cas extrême.
    NavHost(navController = nav, startDestination = Splash) {
        composable<Splash> {
            SplashScreen(
                onFinished = {
                    nav.navigate(Conversations()) {
                        // Le Splash ne doit JAMAIS pouvoir être rejoint via back stack
                        // (back depuis Conversations doit fermer l'app, pas re-jouer
                        // le splash) — d'où `inclusive = true` sur le pop.
                        popUpTo(Splash) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable<Conversations> { entry ->
            val args = entry.toRoute<Conversations>()
            ConversationsScreen(
                archived = args.archived,
                // v1.23.2 — `launchSingleTop` : filet de sécurité contre l'empilement de
                // destinations Thread(id) identiques. La liste peut émettre plusieurs fois
                // l'ouverture pour un seul geste (swipe gauche = « répondre »), et chaque
                // doublon en backstack forçait un appui « retour » supplémentaire pour
                // revenir à la liste. Le verrou côté row corrige la racine ; ceci garantit
                // qu'aucune autre source d'événement ne puisse re-produire le symptôme.
                // Sans risque de collision d'arguments : Conversations est forcément au
                // sommet quand ce callback est invoqué, donc le 1er appel pousse toujours.
                onOpenThread = { id -> nav.navigate(Thread(id)) { launchSingleTop = true } },
                onCompose = { nav.navigate(Compose()) },
                onOpenSettings = { nav.navigate(Settings) },
                onOpenVault = { nav.navigate(Vault) },
                onOpenArchived = { nav.navigate(Conversations(archived = true)) },
                onOpenBlocked = { nav.navigate(Blocked) },
                onOpenAbout = { nav.navigate(About) },
                onBack = if (args.archived) ({ nav.popBackStack() }) else null,
            )
        }
        composable<Vault> {
            VaultScreen(
                onBack = { nav.popBackStack() },
                // v1.23.2 — aligné sur la liste principale : le Coffre partage le même
                // composant `ConversationRow` (`combinedClickable`, qui ne débounce pas
                // un double-tap rapide avant que la destination ne soit committée). Pas
                // de swipe ici, donc risque plus faible, mais la garde doit être la même
                // des deux côtés — un doublon `Thread(id)` en backstack coûte à l'user un
                // appui « retour » supplémentaire.
                onOpenThread = { id -> nav.navigate(Thread(id)) { launchSingleTop = true } },
            )
        }
        composable<Blocked> { BlockedNumbersScreen(onBack = { nav.popBackStack() }) }
        composable<Settings> {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenAbout = { nav.navigate(About) },
                onOpenBackup = { nav.navigate(Backup) },
                onOpenMigration = { nav.navigate(Migration) },
                onOpenBlocked = { nav.navigate(Blocked) },
                onOpenSafetyCall = { nav.navigate(SafetyCallSetup) },
                onOpenEmergency = { nav.navigate(Emergency) },
                onOpenEmergencySetup = { nav.navigate(EmergencySetup) },
                // v1.15.1 — accès à la nouvelle screen Messages programmés.
                onOpenScheduledMessages = { nav.navigate(ScheduledMessages) },
            )
        }
        // v1.15.1 — Liste + annulation des messages programmés.
        composable<ScheduledMessages> {
            com.filestech.sms.ui.screens.scheduled.ScheduledMessagesScreen(
                onBack = { nav.popBackStack() },
            )
        }
        composable<SafetyCallSetup> {
            com.filestech.sms.ui.screens.safetycall.SafetyCallSetupScreen(
                onBack = { nav.popBackStack() },
            )
        }
        composable<Emergency> {
            com.filestech.sms.ui.screens.emergency.EmergencyScreen(
                onBack = { nav.popBackStack() },
                onOpenSetup = { nav.navigate(EmergencySetup) },
            )
        }
        composable<EmergencySetup> {
            com.filestech.sms.ui.screens.emergency.EmergencySetupScreen(
                onBack = { nav.popBackStack() },
                onOpenSafetyCallSetup = { nav.navigate(SafetyCallSetup) },
            )
        }
        composable<Backup> { BackupScreen(onBack = { nav.popBackStack() }) }
        composable<Migration> { MigrationScreen(onBack = { nav.popBackStack() }) }
        composable<About> { AboutScreen(onBack = { nav.popBackStack() }) }
        composable<Lock> {
            LockScreen(
                onUnlocked = { nav.popBackStack(route = Lock, inclusive = true) },
            )
        }
        composable<Thread> { backStackEntry ->
            val args = backStackEntry.toRoute<Thread>()
            ThreadScreen(
                conversationId = args.conversationId,
                onBack = { nav.popBackStack() },
                // v1.4.0 (F5 forward feature) — forward to existing conversation. Pop the source thread
                // so the back stack reads [Conversations -> Thread(dest)] instead of
                // [Conversations -> Thread(source) -> Thread(dest)] which would surprise
                // the user on back press.
                onForwardToConversation = { destId ->
                    nav.popBackStack()
                    nav.navigate(Thread(destId))
                },
                // v1.4.0 (F5 forward feature) — forward to a new recipient: route to ComposeScreen.
                // The IncomingShareHolder payload posted by `stageForward` is still
                // present when ComposeScreen's `onConversationCreated` navigates to
                // the newly-created thread, where `consumeIncomingShareIfAny` picks it
                // up at hydration.
                onForwardToNewContact = {
                    nav.popBackStack()
                    nav.navigate(Compose())
                },
            )
        }
        composable<Compose> { backStackEntry ->
            val args = backStackEntry.toRoute<Compose>()
            ComposeScreen(
                initialAddress = args.initialAddress,
                onBack = { nav.popBackStack() },
                onConversationCreated = { id ->
                    nav.popBackStack()
                    nav.navigate(Thread(id))
                },
            )
        }
    }
}
