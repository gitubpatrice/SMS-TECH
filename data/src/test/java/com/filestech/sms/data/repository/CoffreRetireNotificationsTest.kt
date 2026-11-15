package com.filestech.sms.data.repository

import androidx.room.withTransaction
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.domain.notification.ConversationNotificationCanceller
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 2026-11-15 (v1.30.1, relecture GPT) — **une conversation qui entre au Coffre perd ses notifications affichées.**
 *
 * La promesse du Coffre (« ni nom, ni aperçu, ni icône ») ne tenait que pour les messages À VENIR : la notification
 * d'un message reçu juste avant le déplacement restait dans le volet, nom et aperçu compris. Le premier test l'exige ;
 * le second, contrôle positif, vérifie qu'une SORTIE du Coffre ne retire rien.
 */
class CoffreRetireNotificationsTest {

    private val database = mockk<AppDatabase>()
    private val conversationDao = mockk<ConversationDao>(relaxed = true)
    private val notifications = mockk<ConversationNotificationCanceller>(relaxed = true)

    @BeforeEach
    fun transactionDirecte() {
        // `withTransaction` est une extension statique de Room : on l'exécute telle quelle, sans base.
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
        notifier = notifications,
        contactRepo = mockk(),
        telephonyReader = mockk(),
        phoneIdentity = mockk(),
        eraser = mockk(),
        io = Dispatchers.Unconfined,
    )

    @Test
    fun `entrer au Coffre retire les notifications de chaque conversation deplacee`() = runTest {
        val deplacees = depot().bulkMoveToVault(listOf(7L, 8L), inVault = true)

        assertThat(deplacees).isEqualTo(2)
        coVerify { conversationDao.setInVault(7L, true) }
        verify(exactly = 1) { notifications.cancelAllForConversation(7L) }
        verify(exactly = 1) { notifications.cancelAllForConversation(8L) }
    }

    @Test
    fun `sortir du Coffre ne retire aucune notification`() = runTest {
        depot().bulkMoveToVault(listOf(7L), inVault = false)

        coVerify { conversationDao.setInVault(7L, false) }
        verify(exactly = 0) { notifications.cancelAllForConversation(any()) }
    }
}
