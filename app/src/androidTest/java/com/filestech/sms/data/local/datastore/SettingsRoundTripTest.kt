package com.filestech.sms.data.local.datastore

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards against a field added to [AdvancedSettings] but never wired into the DataStore
 * serialisation — the class of bug that silently shipped `startupDbMigrationsDone` as an in-memory
 * flag `SettingsRepository.write` never persisted (v1.24.0). The in-memory fakes used by unit tests
 * cannot catch it: only a real DataStore round-trip can.
 *
 * Runs on the real app datastore (the emulator is disposable); every flag is reset in `tearDown`.
 */
@RunWith(AndroidJUnit4::class)
class SettingsRoundTripTest {

    private val repo = SettingsRepository(
        context = InstrumentationRegistry.getInstrumentation().targetContext,
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    @Before
    fun reset() = runBlocking { setAllMigrationFlags(false) }

    @After
    fun tearDown() = runBlocking { setAllMigrationFlags(false) }

    private suspend fun setAllMigrationFlags(value: Boolean) {
        repo.update { s ->
            s.copy(
                advanced = s.advanced.copy(
                    unreadResetV180 = value,
                    attachmentsMovedToFilesDirV147 = value,
                    dedupSameNumberV1230 = value,
                    startupDbMigrationsDone = value,
                ),
            )
        }
    }

    @Test
    fun everyStartupMigrationFlag_survivesADataStoreRoundTrip() = runBlocking {
        // Precondition: they start false.
        repo.flow.first().advanced.let { a ->
            assertThat(a.unreadResetV180).isFalse()
            assertThat(a.attachmentsMovedToFilesDirV147).isFalse()
            assertThat(a.dedupSameNumberV1230).isFalse()
            assertThat(a.startupDbMigrationsDone).isFalse()
        }

        setAllMigrationFlags(true)

        // A fresh read must see every flag persisted — this is exactly what the missing
        // `write`/`toAppSettings` mapping broke for `startupDbMigrationsDone`.
        repo.flow.first().advanced.let { a ->
            assertThat(a.unreadResetV180).isTrue()
            assertThat(a.attachmentsMovedToFilesDirV147).isTrue()
            assertThat(a.dedupSameNumberV1230).isTrue()
            assertThat(a.startupDbMigrationsDone).isTrue()
        }
    }
}
