package com.filestech.sms.data.repository

import com.filestech.sms.data.local.db.dao.MessageSearchRow
import com.filestech.sms.domain.model.Conversation
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.28.8 (audit 3 axes, Q2) — la défense en profondeur de la recherche, au niveau du dépôt : ce que
 * les tests du DAO ne couvrent pas, puisqu'ils testent la requête et la relecture des conversations
 * séparément, jamais leur appariement.
 */
class MessageSearchPairingTest {

    private fun conv(id: Long, inVault: Boolean = false) = Conversation(
        id = id,
        threadId = id,
        addresses = emptyList(),
        displayName = "c$id",
        lastMessageAt = 0L,
        lastMessagePreview = null,
        unreadCount = 0,
        pinned = false,
        archived = false,
        muted = false,
        inVault = inVault,
        draft = null,
    )

    private fun ligne(id: Long, convId: Long) = MessageSearchRow(id, convId, date = 1_000L * id, excerpt = "m$id")

    @Test
    fun `un message dont la conversation manque est ecarte`() {
        // La conversation 2 est passée au coffre entre la recherche et la relecture : elle n'est plus lue.
        val hits = apparierResultats(listOf(ligne(10, 1), ligne(20, 2), ligne(11, 1)), listOf(conv(1)))

        assertThat(hits.map { it.messageId }).containsExactly(10L, 11L).inOrder()
    }

    @Test
    fun `une conversation du coffre est ecartee meme si la liste l apporte`() {
        val hits = apparierResultats(listOf(ligne(10, 1), ligne(20, 2)), listOf(conv(1), conv(2, inVault = true)))

        assertThat(hits.map { it.messageId }).containsExactly(10L)
    }

    @Test
    fun `l ordre des lignes est conserve et chaque message porte sa conversation`() {
        val hits = apparierResultats(listOf(ligne(30, 3), ligne(10, 1)), listOf(conv(1), conv(3)))

        assertThat(hits.map { it.messageId to it.conversation.id }).containsExactly(30L to 3L, 10L to 1L).inOrder()
    }

    @Test
    fun `moins de deux caracteres significatifs - rien n est cherche`() {
        assertThat(requeteRecherchable("")).isNull()
        assertThat(requeteRecherchable("a")).isNull()
        assertThat(requeteRecherchable("  a  ")).isNull()
    }

    @Test
    fun `une requete vide apres echappement - rien n est cherche`() {
        assertThat(requeteRecherchable("**")).isNull()
    }

    @Test
    fun `une requete valide est echappee en prefixe`() {
        assertThat(requeteRecherchable("café gare")).isEqualTo("café* gare*")
    }
}
