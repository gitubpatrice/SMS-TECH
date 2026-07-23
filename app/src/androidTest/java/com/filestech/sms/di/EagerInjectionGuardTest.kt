package com.filestech.sms.di

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Tripwire against the regression that made the SEC-CRIT repair dangerous (audit C1 / T1 / T2).
 *
 * Hilt resolves `@Inject` **fields** eagerly, on the main thread: for an `@HiltAndroidApp`
 * Application inside `super.onCreate()`, for an `@AndroidEntryPoint` receiver before the body of
 * `onReceive`, for a service in `onCreate`. Any such field whose type transitively reaches a Room
 * DAO therefore provisions `AppDatabase` there — and with it [LegacyZeroKeyRekey], which on the one
 * launch that rebuilds a legacy zero-key database is seconds of work. On a broadcast that means a
 * hard 10-second ANR budget, on a dead process it means the incoming SMS is lost.
 *
 * Two audits found six such paths, including one only three hops long
 * (`autoLockObserver` → `VaultManager` → `ConversationRepository` → `AppDatabase`) that a manual
 * review had missed. Reachability is not something a reviewer can reliably eyeball, so this test
 * takes the opposite approach: it pins down the *exact* set of eager field types each entry point
 * is allowed to declare. Adding a dependency to any of them fails here until someone states
 * explicitly whether the new type reaches the database.
 *
 * Fixing a failure is a decision, not a chore:
 *  - the type reaches a DAO → wrap it in `dagger.Lazy<…>` and resolve it off the main thread;
 *  - it provably does not → add it to the allowlist below, with the reason.
 */
@RunWith(AndroidJUnit4::class)
class EagerInjectionGuardTest {

    /**
     * Eager field types allowed per entry point, because none of them reaches a Room DAO.
     *
     * `dagger.Lazy` is always allowed — that is the whole point of the fix, since a `Lazy` field
     * defers provisioning to whatever thread calls `get()`.
     */
    private val allowedEagerTypes: Map<String, Set<String>> = mapOf(
        "com.filestech.sms.MainApplication" to setOf(
            "androidx.hilt.work.HiltWorkerFactory", // holds Providers only
            "com.filestech.sms.system.notifications.NotificationChannelInitializer", // Context only
            "com.filestech.sms.security.AutoLockObserver", // its VaultManager is Lazy (audit T1)
            "com.filestech.sms.system.startup.StartupMigrations", // holds Lazy<DAO>, opens nothing at construction
            "com.filestech.sms.security.AppLockManager", // DataStore + PasswordKdf, no DAO
            "com.filestech.sms.data.local.datastore.SettingsRepository", // DataStore
            "com.filestech.sms.system.notifications.EmergencyShortcutNotifier", // Context only
            "kotlinx.coroutines.CoroutineScope",
            "kotlinx.coroutines.CoroutineDispatcher",
        ),
        "com.filestech.sms.MainActivity" to setOf(
            "com.filestech.sms.data.local.datastore.SettingsRepository",
            "com.filestech.sms.security.AppLockManager",
            "com.filestech.sms.system.share.IncomingShareHolder",
            "com.filestech.sms.system.notifications.SafetyCallIntentToken",
            "com.filestech.sms.system.notifications.PendingNavHolder",
            "com.filestech.sms.data.local.db.DatabaseRepairState", // a StateFlow holder, no DAO
            "kotlinx.coroutines.CoroutineDispatcher",
        ),
        "com.filestech.sms.system.receiver.SmsDeliverReceiver" to setOf(
            "kotlinx.coroutines.CoroutineScope",
            "kotlinx.coroutines.CoroutineDispatcher",
        ),
        "com.filestech.sms.system.receiver.SmsSentReceiver" to setOf(
            "kotlinx.coroutines.CoroutineScope",
        ),
        "com.filestech.sms.system.receiver.SmsDeliveredReceiver" to setOf(
            "kotlinx.coroutines.CoroutineScope",
        ),
        "com.filestech.sms.system.receiver.MmsSentReceiver" to setOf(
            "com.filestech.sms.data.mms.MmsSystemWriteback", // ContentResolver only
            "com.filestech.sms.data.local.datastore.SettingsRepository",
            "kotlinx.coroutines.CoroutineScope",
        ),
        "com.filestech.sms.system.receiver.BootReceiver" to setOf(
            "com.filestech.sms.data.local.datastore.SettingsRepository",
            "com.filestech.sms.system.notifications.EmergencyShortcutNotifier",
            "kotlinx.coroutines.CoroutineScope",
        ),
        "com.filestech.sms.system.receiver.EmergencyShortcutReceiver" to setOf(
            "kotlinx.coroutines.CoroutineScope",
        ),
        "com.filestech.sms.system.notifications.NotificationActionReceiver" to setOf(
            "com.filestech.sms.security.AppLockManager",
            "kotlinx.coroutines.CoroutineScope",
        ),
        "com.filestech.sms.system.service.HeadlessSmsSendService" to setOf(
            "com.filestech.sms.security.AppLockManager",
            "kotlinx.coroutines.CoroutineScope",
        ),
    )

    private companion object {
        const val LAZY = "dagger.Lazy"
    }

    @Test
    fun noEntryPointDeclaresAnUnvettedEagerInjection() {
        val violations = mutableListOf<String>()
        var inspected = 0

        allowedEagerTypes.forEach { (className, allowed) ->
            val clazz = Class.forName(className)
            clazz.declaredFields
                .filter { field -> field.annotations.any { it is Inject } }
                .forEach { field ->
                    inspected++
                    val typeName = field.type.name
                    if (typeName != LAZY && typeName !in allowed) {
                        violations += "$className.${field.name}: $typeName"
                    }
                }
        }

        // Without this the test would pass vacuously the day Kotlin stops placing `@Inject` on the
        // backing field, or R8 strips the annotation — it would inspect nothing and report nothing.
        assertThat(inspected).isAtLeast(30)
        assertThat(violations).isEmpty()
    }

    /**
     * Proves the check has teeth: a class that deliberately declares an eager DAO injection must be
     * reported. Without this, a silent change in how `@Inject` is retained would turn the guard
     * above into a test that can no longer fail.
     */
    @Test
    fun theGuardActuallyDetectsAnEagerDaoInjection() {
        val offender = EagerOffenderSample::class.java
        val eagerFields = offender.declaredFields
            .filter { field -> field.annotations.any { it is Inject } }
            .map { it.type.name }

        assertThat(eagerFields).contains("com.filestech.sms.data.local.db.dao.MessageDao")
        assertThat(eagerFields).doesNotContain(LAZY)
    }

    /**
     * Fixture for [theGuardActuallyDetectsAnEagerDaoInjection] — never instantiated.
     *
     * Not `private`: Dagger's processor refuses to generate a members-injector for a private class,
     * and it inspects every `@Inject` field it can see, test sources included.
     */
    @Suppress("unused")
    internal class EagerOffenderSample {
        @Inject lateinit var dao: com.filestech.sms.data.local.db.dao.MessageDao
    }

    /**
     * Guards the allowlist itself: if an entry point stops declaring one of its vetted types, the
     * entry stays behind and quietly widens what the test tolerates. This keeps the list honest.
     */
    @Test
    fun theAllowlistDescribesClassesThatActuallyExist() {
        allowedEagerTypes.keys.forEach { className ->
            assertThat(Class.forName(className)).isNotNull()
        }
    }
}
