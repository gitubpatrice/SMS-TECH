package com.filestech.sms.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.entity.AttachmentEntity
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #59 — la requête de l'écran « Photos et vidéos », sur une vraie base : le filtre de type, l'ordre,
 * et ce qui doit en rester exclu. Une fausse base ne dirait rien du SQL.
 */
@RunWith(AndroidJUnit4::class)
class MediaDeLaConversationTest {

    private lateinit var db: AppDatabase

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            for (id in listOf(CONVERSATION, AUTRE)) {
                db.conversationDao().insert(
                    ConversationEntity(
                        id = id,
                        threadId = id,
                        addressesCsv = "+3360000000$id",
                        displayName = null,
                        lastMessagePreview = "",
                        lastMessageAt = 0L,
                        unreadCount = 0,
                    ),
                )
            }
        }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun message(conversation: Long = CONVERSATION, date: Long, cache: Boolean = false): Long =
        db.messageDao().insert(
            MessageEntity(
                conversationId = conversation,
                telephonyUri = null,
                address = "+33600000001",
                body = "",
                type = MessageType.MMS,
                direction = MessageDirection.INCOMING,
                date = date,
                dateSent = date,
                read = true,
                status = MessageStatus.RECEIVED,
                errorCode = null,
                subId = null,
                scheduledAt = null,
                attachmentsCount = 1,
                hidden = cache,
            ),
        )

    private suspend fun piece(messageId: Long, mime: String, nom: String) {
        db.attachmentDao().insert(
            AttachmentEntity(
                messageId = messageId,
                mimeType = mime,
                fileName = nom,
                sizeBytes = 1,
                localUri = "/files/$nom",
            ),
        )
    }

    private suspend fun noms(conversation: Long = CONVERSATION) =
        db.attachmentDao().observeMediaForConversation(conversation).first().map { it.fileName }

    @Test
    fun seulsLesPhotosEtLesVideosSortent(): Unit = runBlocking {
        val id = message(date = 100L)
        piece(id, "image/jpeg", "photo.jpg")
        piece(id, "video/mp4", "film.mp4")
        piece(id, "IMAGE/PNG", "majuscules.png")
        piece(id, "audio/amr", "voix.amr")
        piece(id, "text/x-vcard", "carte.vcf")
        piece(id, "application/smil", "smil.xml")

        assertThat(noms()).containsExactly("photo.jpg", "film.mp4", "majuscules.png")
    }

    @Test
    fun lesPlusRecentesDAbord_etLesPartiesDUnMessageDansLeurOrdre(): Unit = runBlocking {
        val ancien = message(date = 100L)
        val recent = message(date = 200L)
        piece(ancien, "image/jpeg", "ancienne.jpg")
        piece(recent, "image/jpeg", "recente-1.jpg")
        piece(recent, "image/jpeg", "recente-2.jpg")

        assertThat(noms()).containsExactly("recente-1.jpg", "recente-2.jpg", "ancienne.jpg").inOrder()
    }

    @Test
    fun lesPhotosDUneAutreConversationNeSortentPas(): Unit = runBlocking {
        piece(message(date = 100L), "image/jpeg", "ici.jpg")
        piece(message(conversation = AUTRE, date = 200L), "image/jpeg", "ailleurs.jpg")

        assertThat(noms()).containsExactly("ici.jpg")
        assertThat(noms(AUTRE)).containsExactly("ailleurs.jpg")
    }

    @Test
    fun unMessageCacheDuFilNeMontreRienIciNonPlus(): Unit = runBlocking {
        piece(message(date = 100L), "image/jpeg", "visible.jpg")
        piece(message(date = 200L, cache = true), "image/jpeg", "cachee.jpg")

        assertThat(noms()).containsExactly("visible.jpg")
    }

    private companion object {
        const val CONVERSATION = 1L
        const val AUTRE = 2L
    }
}
