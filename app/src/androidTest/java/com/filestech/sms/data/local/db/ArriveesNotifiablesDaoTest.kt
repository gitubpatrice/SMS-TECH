package com.filestech.sms.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 2026-11-27 (issue #98, profil secondaire) — ce qu'une passe peut notifier : `MessageDao.recusNonLusEntre` et
 * `MessageDao.plusGrandId`, sur un vrai Room.
 *
 * Ce que ces cas fixent : seuls les messages REÇUS et NON LUS d'identifiant dans `]apres, jusqua]` et assez récents
 * sortent — ni un envoi, ni un message lu, ni un message hors des bornes ou trop vieux —, les plus récents d'abord,
 * dans la limite demandée.
 */
@RunWith(AndroidJUnit4::class)
class ArriveesNotifiablesDaoTest {

    private lateinit var db: AppDatabase

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            db.conversationDao().insert(
                ConversationEntity(
                    id = 1,
                    threadId = 1,
                    addressesCsv = ALICE,
                    displayName = "Alice",
                    lastMessageAt = 0,
                    lastMessagePreview = null,
                    unreadCount = 0,
                ),
            )
        }
    }

    @After
    fun tearDown() = db.close()

    private fun message(
        id: Long,
        date: Long = MAINTENANT,
        sens: MessageDirection = MessageDirection.INCOMING,
        lu: Boolean = false,
    ) = MessageEntity(
        id = id,
        conversationId = 1,
        telephonyUri = "content://sms/$id",
        address = ALICE,
        body = "m$id",
        type = MessageType.SMS,
        direction = sens,
        date = date,
        dateSent = null,
        read = lu,
        starred = false,
        status = if (sens == MessageDirection.INCOMING) MessageStatus.RECEIVED else MessageStatus.SENT,
        attachmentsCount = 0,
    )

    private fun inserer(vararg messages: MessageEntity) = runBlocking {
        messages.forEach { db.messageDao().insert(it) }
    }

    private fun candidats(apres: Long, jusqua: Long, depuis: Long = MAINTENANT - HEURE, limite: Int = 20) =
        runBlocking { db.messageDao().recusNonLusEntre(apres, jusqua, depuis, limite) }.map { it.id }

    @Test
    fun plusGrandId_videPuisLePlusGrand() = runBlocking {
        assertThat(db.messageDao().plusGrandId()).isEqualTo(0L)
        inserer(message(3), message(7))
        assertThat(db.messageDao().plusGrandId()).isEqualTo(7L)
    }

    @Test
    fun seulsLesRecusNonLus_sortent() {
        inserer(
            message(1),
            message(2, sens = MessageDirection.OUTGOING),
            message(3, lu = true),
            message(4),
        )
        assertThat(candidats(apres = 0L, jusqua = 10L)).containsExactly(4L, 1L).inOrder()
    }

    @Test
    fun laBorneBasseEstExclue_laBorneHauteIncluse() {
        inserer(message(1), message(2), message(3), message(4))
        assertThat(candidats(apres = 1L, jusqua = 3L)).containsExactly(3L, 2L).inOrder()
    }

    @Test
    fun unMessageTropVieux_neSortPas() {
        inserer(message(1, date = MAINTENANT - 2 * HEURE), message(2, date = MAINTENANT - HEURE), message(3))
        assertThat(candidats(apres = 0L, jusqua = 10L, depuis = MAINTENANT - HEURE)).containsExactly(3L, 2L).inOrder()
    }

    @Test
    fun laLimite_gardeLesPlusRecents() {
        inserer(message(1), message(2), message(3), message(4), message(5))
        assertThat(candidats(apres = 0L, jusqua = 10L, limite = 2)).containsExactly(5L, 4L).inOrder()
    }

    private companion object {
        const val ALICE = "+33612340098"
        const val MAINTENANT = 1_800_000_000_000L
        const val HEURE = 60L * 60L * 1_000L
    }
}
