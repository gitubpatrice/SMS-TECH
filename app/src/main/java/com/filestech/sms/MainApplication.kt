package com.filestech.sms

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.filestech.sms.core.logging.LineNumberDebugTree
import com.filestech.sms.core.logging.NoOpReleaseTree
import com.filestech.sms.data.blocking.BlockedNumbersImporter
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.sync.TelephonySyncManager
import kotlinx.coroutines.flow.first
import com.filestech.sms.di.ApplicationScope
import com.filestech.sms.security.AppLockManager
import com.filestech.sms.security.AutoLockObserver
import com.filestech.sms.system.notifications.NotificationChannelInitializer
import com.filestech.sms.system.scheduler.TelephonySyncWorker
import com.filestech.sms.system.service.KeepAliveService
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class MainApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var notificationChannelInitializer: NotificationChannelInitializer

    @Inject lateinit var autoLockObserver: AutoLockObserver

    @Inject lateinit var appLock: AppLockManager

    @Inject lateinit var telephonySyncManager: TelephonySyncManager

    @Inject lateinit var blockedNumbersImporter: BlockedNumbersImporter

    @Inject lateinit var settingsRepository: SettingsRepository

    /**
     * v1.8.0 (post-audit fix unread badges) — utilisé une fois au cold-start
     * pour recalculer les compteurs `conversations.unread_count` à partir des
     * vrais messages non lus en Room. Purge l'état legacy hérité de v1.7.1
     * (cf. doc [ConversationDao.recomputeAllUnreadCounts]).
     */
    @Inject lateinit var conversationDao: ConversationDao

    /**
     * v1.8.0 (post-audit fix unread badges) — utilisé pour la migration one-shot
     * qui marque tous les messages INCOMING comme lus, en complément du reset
     * `conversations.unread_count`. Cf. doc [MessageDao.markAllIncomingAsRead].
     */
    @Inject lateinit var messageDao: MessageDao

    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.LOG_ENABLED) android.util.Log.DEBUG else android.util.Log.ERROR)
            .build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.LOG_ENABLED) {
            Timber.plant(LineNumberDebugTree())
        } else {
            Timber.plant(NoOpReleaseTree())
        }
        notificationChannelInitializer.ensureDefaultChannels()
        autoLockObserver.register()

        // v1.8.0 (post-audit fix unread badges) — migration ONE-SHOT pour
        // purger les badges hérités v1.7.1 ET les flags `read=0` désynchronisés
        // du système. Exécutée AVANT que l'Activity ne soit créée et que la
        // liste ne soit subscribée, en synchrone (`runBlocking` cap 1 s) pour
        // ne pas laisser l'UI afficher 1 frame de compteurs legacy.
        //
        // Pourquoi le simple recompute SQL ne suffit pas : il s'appuie sur
        // `messages.read` qui est lui-même désynchronisé. Si l'user a lu un
        // message dans Google Messages SANS ouvrir SMS Tech, le système pose
        // `READ=1` mais SMS Tech ne re-lit jamais le `read` pour les messages
        // déjà mirror-és → `messages.read` reste à 0 indéfiniment → recompute
        // calcule `unread_count > 0` à juste titre selon Room, mais incorrect
        // selon l'expérience utilisateur réelle.
        //
        // Solution : reset brutal mais idempotent via flag DataStore. Le user
        // perd l'info "10 vrais messages non lus" si elle existait — acceptable
        // pour purger l'état pourri. Les futurs SMS live arrivent via
        // `SmsDeliverReceiver` avec `read=0` + `touchConversation(+1)`, et
        // le badge s'affichera correctement à partir de là.
        //
        // Le flag `unreadResetV180` empêche cette purge de se rejouer à chaque
        // cold-start (sinon les vrais nouveaux non-lus seraient effacés).
        val migrationStartedAt = System.currentTimeMillis()
        runBlocking {
            withTimeoutOrNull(1_000L) {
                runCatching {
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        val current = settingsRepository.flow.first().advanced
                        if (!current.unreadResetV180) {
                            val touched = messageDao.markAllIncomingAsRead()
                            conversationDao.recomputeAllUnreadCounts()
                            settingsRepository.update { s ->
                                s.copy(advanced = s.advanced.copy(unreadResetV180 = true))
                            }
                            Timber.i(
                                "v1.8.0 migration unreadResetV180 done: marked %d incoming messages as read",
                                touched,
                            )
                        }
                    }
                }.onFailure { Timber.w(it, "v1.8.0 migration unreadResetV180 failed") }
            }
        }
        val migrationElapsed = System.currentTimeMillis() - migrationStartedAt
        if (migrationElapsed >= 1_000L) {
            Timber.w("v1.8.0 migration unreadResetV180 TIMEOUT after %d ms", migrationElapsed)
        }
        // Audit P-P0-5: the historical R6 fix used `runBlocking(IO) { appLock.resolveInitialState() }`
        // here to pre-resolve the lock state before any broadcast receiver could read it. That
        // blocked the main thread for 50-200 ms on DataStore on cold-start. We now kick the
        // resolution off asynchronously — receivers / services that depend on the resolved state
        // call `appLock.ensureResolved()` themselves (idempotent, mutexed) inside their own
        // coroutine context. The main thread is freed and the contract is preserved.
        appScope.launch { appLock.ensureResolved() }
        // Telephony sync: register the system-provider ContentObserver + drain anything that
        // accumulated while the process was dead. Schedule a 12 h safety-net WorkManager job
        // so we still converge even if the observer never fires (rare OEM bug, force-stop).
        telephonySyncManager.start()
        TelephonySyncWorker.schedulePeriodic(this)
        // Order matters at first boot: mirror the OS-wide blocked-numbers list **first**, then
        // kick the SMS import. Otherwise the worker may scan `content://sms` before the Room
        // blocklist is populated, and the user sees blocked correspondents resurface in the
        // very first import (audit "indésirables à l'import"). The importer is fast (< 50 ms
        // typical) and idempotent on re-runs, so paying this serial cost is harmless.
        //
        // The system-side read in `TelephonySyncWorker.runImport` *also* queries
        // `BlockedNumberContract` directly so a fresh-install user who hasn't accepted the
        // default-SMS prompt yet still gets the filter on the next sync tick — this is just
        // belt-and-braces.
        appScope.launch {
            runCatching { blockedNumbersImporter.importFromSystem() }
            // v1.8.0 — second recompute après l'import blocklist + en async.
            // L'import peut avoir purgé des conversations, donc on relance pour
            // ré-aligner les compteurs sur l'état final. Le 1er recompute
            // synchrone (au-dessus, avant Activity) a déjà supprimé l'état
            // legacy v1.7.1, donc ici on capture juste les delta de la purge.
            runCatching { conversationDao.recomputeAllUnreadCounts() }
                .onFailure { Timber.w(it, "recomputeAllUnreadCounts (async post-block) failed") }
            TelephonySyncWorker.enqueueOneShot(this@MainApplication)
        }

        // v1.3.10 — observe le flag `AdvancedSettings.keepAliveService` et démarre /
        // arrête le foreground [KeepAliveService] en conséquence. Cette boucle vit
        // pendant toute la durée du processus (appScope = SupervisorJob applicationwide),
        // garantit l'idempotence (`distinctUntilChanged` filtre les ré-émissions
        // identiques), et couvre :
        //   - cold-start app avec flag déjà ON depuis une session précédente → démarrage
        //   - toggle ON pendant que l'app tourne → démarrage immédiat
        //   - toggle OFF pendant que l'app tourne → arrêt immédiat de la notif persistante
        //   - boot du device (avant ouverture app) → couvert par BootReceiver, complémentaire
        appScope.launch {
            settingsRepository.flow
                .map { it.advanced.keepAliveService }
                .distinctUntilChanged()
                .onEach { enabled ->
                    if (enabled) {
                        KeepAliveService.start(this@MainApplication)
                    } else {
                        KeepAliveService.stop(this@MainApplication)
                    }
                }
                .collect()
        }
    }
}
