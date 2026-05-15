package com.filestech.sms.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.filestech.sms.ui.navigation.Lock
import com.filestech.sms.ui.navigation.Migration
import com.filestech.sms.ui.navigation.Onboarding
import com.filestech.sms.ui.navigation.Settings
import com.filestech.sms.ui.navigation.Thread
import com.filestech.sms.ui.navigation.Vault
import com.filestech.sms.ui.screens.about.AboutScreen
import com.filestech.sms.ui.screens.backup.BackupScreen
import com.filestech.sms.ui.screens.blocked.BlockedNumbersScreen
import com.filestech.sms.ui.screens.compose.ComposeScreen
import com.filestech.sms.ui.screens.conversations.ConversationsScreen
import com.filestech.sms.ui.screens.lock.LockScreen
import com.filestech.sms.ui.screens.migration.MigrationScreen
import com.filestech.sms.ui.screens.onboarding.OnboardingScreen
import com.filestech.sms.ui.screens.settings.SettingsScreen
import com.filestech.sms.ui.screens.thread.ThreadScreen
import com.filestech.sms.ui.screens.vault.VaultScreen

@androidx.compose.runtime.Composable
fun AppRoot(appLock: AppLockManager = hiltViewModel<AppRootViewModel>().appLock) {
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
    LaunchedEffect(isPanicDecoy, nav.currentDestination?.route) {
        if (isPanicDecoy && nav.currentDestination?.route?.contains("Vault") == true) {
            nav.popBackStack(route = Vault, inclusive = true)
        }
    }

    NavHost(navController = nav, startDestination = Conversations()) {
        composable<Conversations> { entry ->
            val args = entry.toRoute<Conversations>()
            ConversationsScreen(
                archived = args.archived,
                onOpenThread = { id -> nav.navigate(Thread(id)) },
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
                onOpenThread = { id -> nav.navigate(Thread(id)) },
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
            )
        }
        composable<Backup> { BackupScreen(onBack = { nav.popBackStack() }) }
        composable<Migration> { MigrationScreen(onBack = { nav.popBackStack() }) }
        composable<About> { AboutScreen(onBack = { nav.popBackStack() }) }
        composable<Onboarding> {
            OnboardingScreen(onFinished = { nav.navigate(Conversations) { popUpTo(Onboarding) { inclusive = true } } })
        }
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
