package com.filestech.sms.data.repository

import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.entity.AttachmentEntity
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.security.AppLockManager
import com.filestech.sms.security.AppLockManager.LockState
import com.filestech.sms.security.VaultSessionState
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Issue #59 — l'écran « Photos et vidéos » ne doit rien montrer de plus que le fil : les photos d'une
 * conversation du Coffre restent cachées Coffre fermé et en session leurre, comme ses messages
 * (`ConversationRepositoryImpl.observeMessages`). Les deux premiers tests sont les contrôles positifs :
 * un filtre qui masquerait tout passerait les deux autres en cassant l'écran.
 */
class ObserveMediaCoffreTest {

    private val photo = AttachmentEntity(
        id = 1,
        messageId = 10,
        mimeType = "image/jpeg",
        fileName = "a.jpg",
        sizeBytes = 3,
        localUri = "/files/a.jpg",
    )

    private fun visible(inVault: Boolean, verrou: LockState, coffreOuvert: Boolean): List<Long> {
        val conversationDao = mockk<ConversationDao>()
        every { conversationDao.observeById(CONVERSATION) } returns flowOf(
            ConversationEntity(
                id = CONVERSATION,
                threadId = CONVERSATION,
                addressesCsv = "+33600000001",
                displayName = null,
                lastMessagePreview = "",
                lastMessageAt = 0L,
                unreadCount = 0,
                inVault = inVault,
            ),
        )
        val attachmentDao = mockk<AttachmentDao>()
        every { attachmentDao.observeMediaForConversation(CONVERSATION) } returns flowOf(listOf(photo))
        val appLock = mockk<AppLockManager>()
        every { appLock.state } returns MutableStateFlow(verrou)
        val coffre = VaultSessionState()
        if (coffreOuvert) coffre.markUnlocked()

        val depot = ConversationRepositoryImpl(
            database = mockk(),
            conversationDao = conversationDao,
            messageDao = mockk(),
            attachmentDao = attachmentDao,
            appLock = appLock,
            vaultSession = coffre,
            blockedRepo = mockk(),
            notifier = mockk(),
            contactRepo = mockk(),
            telephonyReader = mockk(),
            phoneIdentity = mockk(),
            eraser = mockk(),
            io = Dispatchers.Unconfined,
        )
        var resultat = emptyList<Long>()
        runTest { resultat = depot.observeMedia(CONVERSATION).first().map { it.id } }
        return resultat
    }

    @Test
    fun `hors du coffre, les photos se voient, en session leurre aussi`() {
        assertThat(visible(inVault = false, LockState.Unlocked, coffreOuvert = false)).containsExactly(1L)
        assertThat(visible(inVault = false, LockState.PanicDecoy, coffreOuvert = false)).containsExactly(1L)
    }

    @Test
    fun `dans le coffre ouvert, les photos se voient`() {
        assertThat(visible(inVault = true, LockState.Unlocked, coffreOuvert = true)).containsExactly(1L)
    }

    @Test
    fun `dans le coffre ferme, rien ne se voit`() {
        assertThat(visible(inVault = true, LockState.Unlocked, coffreOuvert = false)).isEmpty()
    }

    @Test
    fun `en session leurre, rien du coffre ne se voit, meme marque ouvert`() {
        assertThat(visible(inVault = true, LockState.PanicDecoy, coffreOuvert = true)).isEmpty()
    }

    private companion object {
        const val CONVERSATION = 7L
    }
}
