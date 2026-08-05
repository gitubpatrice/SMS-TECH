package com.filestech.sms.system.startup

import android.content.Context
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.domain.settings.AppSettings
import com.google.common.truth.Truth.assertThat
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
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

    /**
     * 🔴 v1.27.2 — SANS CECI, LES COMPTES DE MOCK S ACCUMULENT ENTRE LES TESTS.
     *
     * Les mocks sont des proprietes de la classe et survivent d une methode a l autre : un
     * `coVerify(exactly = 1)` voyait donc les appels des tests precedents, et le resultat dependait
     * de l ORDRE d execution. Les tests existants passaient par chance — les nouveaux, ajoutes pour
     * LP-05, ont revele le probleme en echouant avec « 2 matching calls found, but needs exactly 1 »
     * en suite complete alors qu ils passaient en isolation.
     *
     * Un test dont le verdict depend de ses voisins ne prouve rien. On repart d une ardoise propre.
     */
    @BeforeEach
    fun resetMocks() {
        clearMocks(messageDao, conversationDao, attachmentDao, mirror, answers = false)
        coEvery { attachmentDao.findByLocalUriPrefix(any()) } returns emptyList()
    }

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
    fun globalGuardSet_skipsEveryLegacyMigration() = runTest {
        coEvery { mirror.dedupeSameNumberConversations() } returns false
        val settings = fakeSettings(
            AppSettings().copy(
                advanced = AppSettings().advanced.copy(startupDbMigrationsDone = true),
            ),
        ).first

        migrations(settings).run()

        // The whole point: an up-to-date install runs none of the LEGACY migrations.
        coVerify(exactly = 0) { messageDao.markAllIncomingAsRead() }
        coVerify(exactly = 0) { conversationDao.recomputeAllUnreadCounts() }
        coVerify(exactly = 0) { attachmentDao.findByLocalUriPrefix(any()) }
    }

    /**
     * 🔴 v1.27.2 (audit Codex du 2026-08-05, LP-05 / LP-07) — LE TEST QUE CODEX RECLAMAIT.
     *
     * Il part de l'etat exact d'une installation VICTIME des deux defauts : tous les anciens
     * drapeaux a `true`, court-circuit global compris. C'est la signature du probleme, pas son
     * absence — une base qui a deduplique sous une region fausse a precisement pose
     * `dedupSameNumberV1230`.
     *
     * Tant que les reparations vivaient derriere `startupDbMigrationsDone`, elles ne tournaient
     * JAMAIS sur le parc qu'elles visent. Ce test echoue si on les y remet.
     */
    @Test
    fun allLegacyFlagsSet_stillRunsTheV1272Repairs() = runTest {
        coEvery { mirror.dedupeSameNumberConversations() } returns false // base propre
        val (settings, state) = fakeSettings(
            AppSettings().copy(
                advanced = AppSettings().advanced.copy(
                    startupDbMigrationsDone = true,
                    unreadResetV180 = true,
                    attachmentsMovedToFilesDirV147 = true,
                    dedupSameNumberV1230 = true,
                    staleConversationPreviewsRepairedV1240 = true,
                ),
            ),
        )

        migrations(settings).run()

        // Les deux reparations v1.27.2 ont bien tourne malgre le court-circuit global…
        coVerify(exactly = 1) { mirror.dedupeSameNumberConversations() }
        coVerify(exactly = 1) { conversationDao.deleteAllEmptyConversations() }
        // …et aucune migration heritee n'a ete rejouee au passage.
        coVerify(exactly = 0) { messageDao.markAllIncomingAsRead() }

        val advanced = state.first().advanced
        assertThat(advanced.identityDedupRepairedV1272).isTrue()
        assertThat(advanced.emptyConversationsPurgedV1272).isTrue()
    }

    /**
     * LP-05 garde la semantique « on ne memorise que sur une base propre » : tant que la passe
     * fusionne encore, le drapeau reste faux et on rejoue au demarrage suivant.
     */
    @Test
    fun v1272IdentityRepairStillMerging_doesNotRecordItsFlag() = runTest {
        coEvery { mirror.dedupeSameNumberConversations() } returns true // fusionne encore
        val (settings, state) = fakeSettings(
            AppSettings().copy(
                advanced = AppSettings().advanced.copy(
                    startupDbMigrationsDone = true,
                    dedupSameNumberV1230 = true,
                ),
            ),
        )

        migrations(settings).run()

        val advanced = state.first().advanced
        assertThat(advanced.identityDedupRepairedV1272).isFalse()
        // La purge des coquilles, elle, est idempotente : son drapeau se pose des la premiere passe.
        assertThat(advanced.emptyConversationsPurgedV1272).isTrue()
    }

    @Test
    fun freshState_runsEveryMigration_thenRecordsGlobalCompletion() = runTest {
        coEvery { mirror.dedupeSameNumberConversations() } returns false // base clean
        val (settings, state) = fakeSettings(AppSettings())

        migrations(settings).run()

        coVerify(exactly = 1) { messageDao.markAllIncomingAsRead() }
        coVerify(exactly = 1) { conversationDao.recomputeAllUnreadCounts() }
        coVerify(exactly = 1) { attachmentDao.findByLocalUriPrefix(any()) }
        // Deux appels : la reparation v1.27.2 (LP-05) puis la passe heritee. Sur une install
        // neuve les deux tournent, et c'est sans consequence — la dedup est idempotente.
        coVerify(exactly = 2) { mirror.dedupeSameNumberConversations() }

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

        // Already-done migrations are skipped by their own guard.
        coVerify(exactly = 0) { messageDao.markAllIncomingAsRead() }
        coVerify(exactly = 0) { attachmentDao.findByLocalUriPrefix(any()) }
        // v1.27.2 (audit Codex du 2026-08-05, LP-05) — DEUX passes de deduplication desormais,
        // et c'est voulu : l'ancienne (`dedupSameNumberV1230`) et la reparation sous la regle
        // d'identite E.164 (`identityDedupRepairedV1272`), qui doit rejouer meme sur une base
        // s'etant deja declaree propre sous l'ancienne region. Meme raisonnement que
        // `freshState_runsEveryMigration_thenRecordsGlobalCompletion` : la dedup est idempotente,
        // donc la faire tourner deux fois est sans consequence.
        coVerify(exactly = 2) { mirror.dedupeSameNumberConversations() }
    }
}
