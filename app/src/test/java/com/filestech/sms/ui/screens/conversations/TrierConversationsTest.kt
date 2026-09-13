package com.filestech.sms.ui.screens.conversations

import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.settings.SortMode
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.28.8 (issue #17) — les conversations épinglées sont en tête dans TOUS les tris.
 *
 * Avant, le tri par défaut (`DATE`) les ignorait : épingler ne changeait rien à l'ordre.
 */
class TrierConversationsTest {

    private fun conv(id: Long, at: Long, pinned: Boolean = false, unread: Int = 0) = Conversation(
        id = id,
        threadId = id,
        addresses = emptyList(),
        displayName = "c$id",
        lastMessageAt = at,
        lastMessagePreview = null,
        unreadCount = unread,
        pinned = pinned,
        archived = false,
        muted = false,
        inVault = false,
        draft = null,
    )

    @Test
    fun `tri par date - une epinglee ancienne passe devant les plus recentes`() {
        val rows = listOf(conv(1, at = 300), conv(2, at = 100, pinned = true), conv(3, at = 200))

        assertThat(trierConversations(rows, SortMode.DATE).map { it.id })
            .containsExactly(2L, 1L, 3L).inOrder()
    }

    @Test
    fun `non lus d abord - l epinglee lue reste devant les non lues`() {
        val rows = listOf(
            conv(1, at = 300, unread = 2),
            conv(2, at = 100, pinned = true),
            conv(3, at = 200),
            conv(4, at = 50, unread = 1),
        )

        assertThat(trierConversations(rows, SortMode.UNREAD_FIRST).map { it.id })
            .containsExactly(2L, 1L, 4L, 3L).inOrder()
    }

    @Test
    fun `plusieurs epinglees sont rangees entre elles par le critere du tri`() {
        val rows = listOf(
            conv(1, at = 100, pinned = true),
            conv(2, at = 300, pinned = true),
            conv(3, at = 200),
        )

        assertThat(trierConversations(rows, SortMode.DATE).map { it.id })
            .containsExactly(2L, 1L, 3L).inOrder()
    }
}
