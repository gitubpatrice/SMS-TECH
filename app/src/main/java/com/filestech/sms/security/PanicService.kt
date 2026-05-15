package com.filestech.sms.security

import com.filestech.sms.core.crypto.KeystoreManager
import com.filestech.sms.data.local.datastore.SecurityStore
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.DatabaseKeyManager
import com.filestech.sms.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hard wipe of all locally stored sensitive data. Triggered by user action (settings →
 * "Supprimer toutes mes données"). Order matters: drop the SQLCipher key file first so even
 * a crash mid-wipe leaves the DB unreadable.
 */
@Singleton
class PanicService @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val database: AppDatabase,
    private val keyManager: DatabaseKeyManager,
    private val keystore: KeystoreManager,
    private val securityStore: SecurityStore,
    private val settings: SettingsRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun nukeEverything(): Unit = withContext(io) {
        // Order matters (audit F29):
        //  1. Close the Room/SQLCipher database synchronously so no transaction can re-write
        //     after we delete its on-disk files.
        //  2. Drop the wrapped DB key BEFORE touching the actual database files — if anything
        //     crashes mid-wipe, the residual DB is unreadable.
        //  3. Drop the Keystore aliases so the wrapped key blob can't be reconstructed.
        //  4. Delete the database + its WAL/SHM sidecars via Context.deleteDatabase (the only
        //     way to also nuke `<db>-journal`, `<db>-wal` and `<db>-shm`).
        //  5. Wipe cache + exports + attachments.
        //  6. Reset preferences.
        runCatching { database.close() }.onFailure { Timber.w(it, "PanicService: db close") }
        runCatching { keyManager.destroyKeyFile() }.onFailure { Timber.w(it, "destroy key file") }
        runCatching {
            keystore.deleteKey(KeystoreManager.ALIAS_DB_MASTER)
            keystore.deleteKey(KeystoreManager.ALIAS_VAULT_KEK)
            keystore.deleteKey(KeystoreManager.ALIAS_SETTINGS_AEAD)
            keystore.deleteKey(KeystoreManager.ALIAS_PANIC_DECOY)
        }.onFailure { Timber.w(it, "delete keystore aliases") }
        runCatching { context.deleteDatabase(com.filestech.sms.data.local.db.AppDatabase.DATABASE_NAME) }
            .onFailure { Timber.w(it, "deleteDatabase") }
        runCatching { securityStore.clearPin() }
        runCatching { securityStore.clearPanic() }
        // Audit S-P2-2: clearPin / clearPanic above remove the credential snapshots themselves
        // but leave the surrounding bookkeeping (`failCount`, `lockoutUntil`) untouched in the
        // DataStore. After a wipe the user re-onboards with a brand-new lock; if the previous
        // session had been close to the lockout threshold, the new setup would inherit those
        // counters and lock the user out before they had a chance to authenticate.
        runCatching {
            securityStore.setFailCount(0)
            securityStore.setLockoutUntil(0L)
        }
        runCatching {
            File(context.filesDir, "mms_attachments").deleteRecursively()
            File(context.filesDir, "exports").deleteRecursively()
            File(context.filesDir, "db").deleteRecursively()
            context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        }.onFailure { Timber.w(it, "wipe file dirs") }
        runCatching { settings.update { com.filestech.sms.data.local.datastore.AppSettings() } }
    }
}
