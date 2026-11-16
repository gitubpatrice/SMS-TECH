package com.filestech.sms.data.repository

import androidx.room.withTransaction
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 2026-11-16 (v1.31.0) — **l'archivage d'une sélection tient en une transaction**, comme le déplacement au Coffre :
 * toutes les conversations ou aucune. Le nombre rendu est celui des lignes réellement modifiées : une conversation
 * supprimée entre la sélection et l'action ne compte pas.
 */
class ArchivageGroupeTest {

    private val database = mockk<AppDatabase>()
    private val conversationDao = mockk<ConversationDao>()

    @BeforeEach
    fun transactionDirecte() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Int>()) } coAnswers {
            secondArg<suspend () -> Int>().invoke()
        }
    }

    @AfterEach
    fun liberer() = unmockkStatic("androidx.room.RoomDatabaseKt")

    private fun depot() = ConversationRepositoryImpl(
        database = database,
        conversationDao = conversationDao,
        messageDao = mockk(),
        attachmentDao = mockk(),
        appLock = mockk(),
        vaultSession = mockk(),
        blockedRepo = mockk(),
        notifier = mockk(),
        contactRepo = mockk(),
        telephonyReader = mockk(),
        phoneIdentity = mockk(),
        eraser = mockk(),
        io = Dispatchers.Unconfined,
    )

    @Test
    fun `une seule transaction, et seules les lignes modifiees comptent`() = runTest {
        coEvery { conversationDao.setArchived(1L, true) } returns 1
        coEvery { conversationDao.setArchived(2L, true) } returns 0 // supprimée entre-temps
        coEvery { conversationDao.setArchived(3L, true) } returns 1

        val archivees = depot().bulkSetArchived(listOf(1L, 2L, 3L), archived = true)

        assertThat(archivees).isEqualTo(2)
        coVerify(exactly = 1) { database.withTransaction(any<suspend () -> Int>()) }
        coVerify(exactly = 1) { conversationDao.setArchived(3L, true) }
    }

    @Test
    fun `une selection vide n'ouvre aucune transaction`() = runTest {
        assertThat(depot().bulkSetArchived(emptyList(), archived = false)).isEqualTo(0)
        coVerify(exactly = 0) { database.withTransaction(any<suspend () -> Int>()) }
    }
}
