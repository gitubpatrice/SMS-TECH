package com.filestech.sms.ui.screens.conversations

import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.MessageSearchHit
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-11-06 — le dossier des conversations bloquées.
 *
 * Une conversation bloquée n'apparaît QUE dans le dossier, archivée ou non ; la liste principale et les
 * Archives n'en montrent aucune ; l'icône du dossier s'affiche dès qu'il en existe une, même archivée ; et
 * la recherche dans les messages ne ramène pas le texte d'une conversation bloquée dans la liste.
 *
 * [repartir] reçoit UNE lecture de toutes les conversations hors coffre, dans l'ordre de la requête : les
 * jeux ci-dessous mêlent donc archivées et non archivées, comme la base.
 */
class RepartitionConversationsTest {

    private fun conv(id: Long, archived: Boolean = false, blocked: Boolean = false) = Conversation(
        id = id,
        threadId = id,
        addresses = emptyList(),
        displayName = "c$id",
        lastMessageAt = id,
        lastMessagePreview = null,
        unreadCount = 0,
        pinned = false,
        archived = archived,
        muted = false,
        inVault = false,
        draft = null,
        blocked = blocked,
    )

    private val horsCoffre = listOf(
        conv(5, archived = true, blocked = true),
        conv(1),
        conv(4, archived = true),
        conv(2, blocked = true),
        conv(3),
    )

    @Test
    fun `liste principale - les actives non bloquees, dans l'ordre de la lecture, et l'icone du dossier`() {
        val r = repartir(VueConversations.PRINCIPALE, horsCoffre)

        assertThat(r.conversations.map { it.id }).containsExactly(1L, 3L).inOrder()
        assertThat(r.bloqueesExistent).isTrue()
    }

    @Test
    fun `archives - les archivees non bloquees`() {
        val r = repartir(VueConversations.ARCHIVES, horsCoffre)

        assertThat(r.conversations.map { it.id }).containsExactly(4L)
    }

    /** Une bloquée archivée quitte les Archives pour le dossier : elle n'est pas perdue entre les deux. */
    @Test
    fun `dossier - toutes les bloquees, archivees ou non, dans l'ordre de la lecture`() {
        val r = repartir(VueConversations.BLOQUEES, horsCoffre)

        assertThat(r.conversations.map { it.id }).containsExactly(5L, 2L).inOrder()
        assertThat(r.idsBloques).containsExactly(2L, 5L)
    }

    /** Seule une bloquée ARCHIVÉE existe : l'icône doit quand même s'afficher, sinon elle est introuvable. */
    @Test
    fun `icone - une seule bloquee, archivee, suffit`() {
        val r = repartir(VueConversations.PRINCIPALE, listOf(conv(1), conv(5, archived = true, blocked = true)))

        assertThat(r.bloqueesExistent).isTrue()
        assertThat(r.conversations.map { it.id }).containsExactly(1L)
    }

    /** Contrôle négatif : sans conversation bloquée, pas d'icône, et la liste est intacte. */
    @Test
    fun `aucune bloquee - pas d'icone, liste intacte`() {
        val r = repartir(VueConversations.PRINCIPALE, listOf(conv(1), conv(4, archived = true), conv(3)))

        assertThat(r.bloqueesExistent).isFalse()
        assertThat(r.idsBloques).isEmpty()
        assertThat(r.conversations.map { it.id }).containsExactly(1L, 3L).inOrder()
        assertThat(repartir(VueConversations.BLOQUEES, listOf(conv(1))).conversations).isEmpty()
    }

    /** Archiver la seule bloquée depuis le dossier : elle y reste, et l'icône aussi. */
    @Test
    fun `dossier - archiver une bloquee ne la fait pas sortir du dossier`() {
        val avant = repartir(VueConversations.BLOQUEES, listOf(conv(1), conv(2, blocked = true)))
        val apres = repartir(VueConversations.BLOQUEES, listOf(conv(1), conv(2, archived = true, blocked = true)))

        assertThat(avant.conversations.map { it.id }).containsExactly(2L)
        assertThat(apres.conversations.map { it.id }).containsExactly(2L)
        assertThat(apres.bloqueesExistent).isTrue()
    }

    @Test
    fun `recherche - les messages d'une conversation bloquee sont ecartes`() {
        val hits = listOf(hit(1, conv(1)), hit(2, conv(2)), hit(3, conv(3)))

        assertThat(sansConversationsBloquees(hits, setOf(2L)).map { it.messageId }).containsExactly(1L, 3L).inOrder()
    }

    @Test
    fun `recherche - sans bloquee, rien n'est ecarte`() {
        val hits = listOf(hit(1, conv(1)), hit(2, conv(2)))

        assertThat(sansConversationsBloquees(hits, emptySet())).isEqualTo(hits)
    }

    private fun hit(messageId: Long, conversation: Conversation) =
        MessageSearchHit(messageId = messageId, date = messageId, excerpt = "x", conversation = conversation)
}
