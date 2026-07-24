package com.filestech.sms.system.startup

import android.content.Context
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.domain.settings.AppSettings
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Pins down the behaviour of the cold-start migration consolidation (v1.24.0, Étage 1.2).
 *
 * The three migrations were moved verbatim; what is genuinely new — and what could silently break
 * things — is the global short-circuit and the fact that they now share one settings read and run
 * sequentially. These tests prove: the guard skips every database access when set, the migrations
 * still run and set their flags when it is not, the guard is only recorded once all three
 * individual flags are, and the dedup flag keeps its "only when clean" semantics.
 */
class StartupMigrationsTest {

    @TempDir
    lateinit var tmp: File

    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val conversationDao = mockk<ConversationDao>(relaxed = true)
    private val attachmentDao = mockk<AttachmentDao> {
        coEvery { findByLocalUriPrefix(any()) } returns emptyList()
    }
    private val mirror = mockk<ConversationMirror>()

    private fun context(): Context = mockk {
        every { cacheDir } returns File(tmp, "cache").apply { mkdirs() }
        every { filesDir } returns File(tmp, "files").apply { mkdirs() }
    }

    /** A SettingsRepository backed by an in-memory state whose `update` applies the transform. */
    private fun fakeSettings(initial: AppSettings): Pair<SettingsRepository, MutableStateFlow<AppSettings>> {
        val state = MutableStateFlow(initial)
        val repo = mockk<SettingsRepository> {
            every { flow } returns state
            coEvery { update(any()) } coAnswers {
                val transform = firstArg<(AppSettings) -> AppSettings>()
                state.value = transform(state.value)
            }
        }
        return repo to state
    }

    private fun migrations(settings: SettingsRepository) = StartupMigrations(
        settings = settings,
        messageDao = dagger.Lazy { messageDao },
        conversationDao = dagger.Lazy { conversationDao },
        attachmentDao = dagger.Lazy { attachmentDao },
        conversationMirror = dagger.Lazy { mirror },
        context = context(),
        io = Dispatchers.Unconfined,
    )

    @Test
    fun globalGuardSet_skipsEveryDatabaseAccess() = runTest {
        val settings = fakeSettings(
            AppSettings().copy(
                advanced = AppSettings().advanced.copy(startupDbMigrationsDone = true),
            ),
        ).first

        migrations(settings).run()

        // The whole point: an up-to-date install opens nothing.
        coVerify(exactly = 0) { messageDao.markAllIncomingAsRead() }
        coVerify(exactly = 0) { conversationDao.recomputeAllUnreadCounts() }
        coVerify(exactly = 0) { attachmentDao.findByLocalUriPrefix(any()) }
        coVerify(exactly = 0) { mirror.dedupeSameNumberConversations() }
    }

    @Test
    fun freshState_runsEveryMigration_thenRecordsGlobalCompletion() = runTest {
        coEvery { mirror.dedupeSameNumberConversations() } returns false // base clean
        val (settings, state) = fakeSettings(AppSettings())

        migrations(settings).run()

        coVerify(exactly = 1) { messageDao.markAllIncomingAsRead() }
        coVerify(exactly = 1) { conversationDao.recomputeAllUnreadCounts() }
        coVerify(exactly = 1) { attachmentDao.findByLocalUriPrefix(any()) }
        coVerify(exactly = 1) { mirror.dedupeSameNumberConversations() }

        val advanced = state.first().advanced
        assertThat(advanced.unreadResetV180).isTrue()
        assertThat(advanced.attachmentsMovedToFilesDirV147).isTrue()
        assertThat(advanced.dedupSameNumberV1230).isTrue()
        assertThat(advanced.startupDbMigrationsDone).isTrue()
    }

    @Test
    fun dedupStillMerging_keepsBothDedupAndGlobalFlagUnset_soItReRunsNextColdStart() = runTest {
        coEvery { mirror.dedupeSameNumberConversations() } returns true // still found duplicates
        val (settings, state) = fakeSettings(AppSettings())

        migrations(settings).run()

        val advanced = state.first().advanced
        // The two migrations that finished record their flags…
        assertThat(advanced.unreadResetV180).isTrue()
        assertThat(advanced.attachmentsMovedToFilesDirV147).isTrue()
        // …but dedup did not, so the global guard must stay off and let the next cold start retry.
        assertThat(advanced.dedupSameNumberV1230).isFalse()
        assertThat(advanced.startupDbMigrationsDone).isFalse()
    }

    @Test
    fun individualFlagsAlreadySet_areNotReRun() = runTest {
        coEvery { mirror.dedupeSameNumberConversations() } returns false
        val (settings, _) = fakeSettings(
            AppSettings().copy(
                // Global guard OFF, but unread+attachments already done individually.
                advanced = AppSettings().advanced.copy(
                    unreadResetV180 = true,
                    attachmentsMovedToFilesDirV147 = true,
                ),
            ),
        )

        migrations(settings).run()

        // Already-done migrations are skipped by their own guard; only dedup runs.
        coVerify(exactly = 0) { messageDao.markAllIncomingAsRead() }
        coVerify(exactly = 0) { attachmentDao.findByLocalUriPrefix(any()) }
        coVerify(exactly = 1) { mirror.dedupeSameNumberConversations() }
    }
}
