package com.filestech.sms.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.data.local.db.entity.MessageEntity
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
 * Covers the bounded thread query introduced in v1.24.0 (finding A).
 *
 * The subtle part is ordering. `MessageDao.observeForConversation` sorts by `date` alone, which is
 * harmless without a `LIMIT`; add one and ties become a correctness problem — a multipart MMS
 * lands with identical timestamps, and without a total order the window boundary is
 * non-deterministic, so a row can be dropped or duplicated between emissions. These tests pin the
 * `date, id` tie-break down.
 */
@RunWith(AndroidJUnit4::class)
class MessageWindowDaoTest {

    private lateinit var db: AppDatabase

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            db.conversationDao().upsert(
                ConversationEntity(
                    id = 1,
                    threadId = 1,
                    addressesCsv = "+33612345678",
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

    private fun message(id: Long, date: Long, body: String = "m$id") = MessageEntity(
        id = id,
        conversationId = 1,
        telephonyUri = "content://sms/$id",
        address = "+33612345678",
        body = body,
        type = MessageType.SMS,
        direction = MessageDirection.INCOMING,
        date = date,
        dateSent = null,
        read = true,
        starred = false,
        status = MessageStatus.DELIVERED,
        attachmentsCount = 0,
    )

    /** Sets the conversation's stored last-message metadata (there is no dedicated DAO setter). */
    private fun setConvPreview(at: Long, preview: String) = runBlocking {
        db.conversationDao().update(
            ConversationEntity(
                id = 1,
                threadId = 1,
                addressesCsv = "+33612345678",
                displayName = "Alice",
                lastMessageAt = at,
                lastMessagePreview = preview,
                unreadCount = 0,
            ),
        )
    }

    private fun seed(count: Int, sameTimestamp: Boolean = false) = runBlocking {
        (1..count).forEach { i ->
            db.messageDao().insert(message(i.toLong(), if (sameTimestamp) 1_000L else 1_000L + i))
        }
    }

    @Test
    fun window_returnsTheMostRecentMessages_oldestFirst() = runBlocking {
        seed(500)

        val window = db.messageDao().observeWindowForConversation(1, 200).first()

        assertThat(window).hasSize(200)
        // Oldest-first inside the window, and the window is the tail of the thread.
        assertThat(window.first().id).isEqualTo(301)
        assertThat(window.last().id).isEqualTo(500)
        assertThat(window.map { it.date }).isInOrder()
    }

    @Test
    fun window_smallerThanTheThread_reportsEveryRowThroughStats() = runBlocking {
        seed(500)

        val stats = db.messageDao().observeStatsForConversation(1).first()

        // Stats describe the CONVERSATION, never the window — otherwise the info panel would
        // claim a 500-message thread holds 200 and started at the window's boundary.
        assertThat(stats.total).isEqualTo(500)
        assertThat(stats.firstAt).isEqualTo(1_001L)
        assertThat(stats.lastAt).isEqualTo(1_500L)
    }

    @Test
    fun widerWindow_isASuperset_ofTheNarrowerOne() = runBlocking {
        seed(500)

        val narrow = db.messageDao().observeWindowForConversation(1, 200).first().map { it.id }
        val wide = db.messageDao().observeWindowForConversation(1, 400).first().map { it.id }

        assertThat(wide).hasSize(400)
        assertThat(wide).containsAtLeastElementsIn(narrow)
        // Growing the window only ever prepends older messages.
        assertThat(wide.takeLast(narrow.size)).isEqualTo(narrow)
    }

    /**
     * The regression this ordering exists for: with `ORDER BY date` alone, ties at the window
     * boundary are resolved arbitrarily by SQLite, so the same row could appear twice across two
     * emissions, or vanish entirely.
     */
    @Test
    fun identicalTimestamps_produceAStableDeterministicWindow() = runBlocking {
        seed(50, sameTimestamp = true)

        val first = db.messageDao().observeWindowForConversation(1, 20).first().map { it.id }
        val second = db.messageDao().observeWindowForConversation(1, 20).first().map { it.id }

        assertThat(first).isEqualTo(second)
        assertThat(first).hasSize(20)
        // Ties broken by id: the 20 most recent are ids 31..50, ascending.
        assertThat(first).isEqualTo((31L..50L).toList())
        assertThat(first).containsNoDuplicates()
    }

    @Test
    fun windowLargerThanTheThread_returnsEverythingWithoutPadding() = runBlocking {
        seed(10)

        val window = db.messageDao().observeWindowForConversation(1, 200).first()

        assertThat(window).hasSize(10)
        assertThat(window.map { it.id }).isEqualTo((1L..10L).toList())
    }

    /**
     * Reaction sentinels (`body = ''`, no attachment, no emoji) are hidden by the unbounded query;
     * the window and the stats must apply the very same predicate, otherwise `hasMore` would be
     * computed against a total the window can never reach and the "load older" control would
     * never disappear.
     */
    /**
     * The delete-preview bug found on a real backup (2026-07-23): deleting the last message of a
     * thread left `conversations.last_message_preview` pointing at the deleted message.
     */
    @Test
    fun refreshConversationPreview_updatesToTheNewestRemainingMessage() = runBlocking {
        db.messageDao().insert(message(1, 1_000L, body = "older"))
        db.messageDao().insert(message(2, 2_000L, body = "1538")) // the "last" message
        setConvPreview(2_000L, "1538")

        // Delete the newest, then refresh — exactly what deleteMessage now does.
        db.messageDao().delete(2)
        db.messageDao().refreshConversationPreview(1)

        val conv = db.conversationDao().findById(1)!!
        assertThat(conv.lastMessagePreview).isEqualTo("older")
        assertThat(conv.lastMessageAt).isEqualTo(1_000L)
    }

    @Test
    fun refreshConversationPreview_emptiesThePreviewWhenNoMessageRemains() = runBlocking {
        db.messageDao().insert(message(1, 1_000L, body = "only"))
        setConvPreview(1_000L, "only")

        db.messageDao().delete(1)
        db.messageDao().refreshConversationPreview(1)

        val conv = db.conversationDao().findById(1)!!
        assertThat(conv.lastMessagePreview).isNull()
        assertThat(conv.lastMessageAt).isEqualTo(0L)
    }

    /**
     * The one-shot repair for previews left stale by pre-1.24.0 deletions. It must fix a
     * conversation whose stored `last_message_at` is ahead of its real messages, and it must NOT
     * touch a healthy conversation (no reordering, no label loss).
     */
    @Test
    fun repairStaleConversationPreviews_fixesOnlyTheStaleOnes() = runBlocking {
        // Conversation 1: stale — points at a deleted message newer than what remains.
        db.messageDao().insert(message(1, 1_000L, body = "still here"))
        setConvPreview(5_000L, "deleted-1538") // last_message_at ahead of any real message

        // Conversation 2: healthy — preview matches its real newest message.
        db.conversationDao().upsert(
            ConversationEntity(
                id = 2, threadId = 2, addressesCsv = "+33600000002", displayName = "Bob",
                lastMessageAt = 3_000L, lastMessagePreview = "hi Bob", unreadCount = 0,
            ),
        )
        db.messageDao().insert(
            message(2, 3_000L, body = "hi Bob").copy(conversationId = 2, telephonyUri = "content://sms/2"),
        )

        val fixed = db.messageDao().repairStaleConversationPreviews()

        assertThat(fixed).isEqualTo(1) // only the stale one
        val stale = db.conversationDao().findById(1)!!
        assertThat(stale.lastMessagePreview).isEqualTo("still here")
        assertThat(stale.lastMessageAt).isEqualTo(1_000L)
        // Healthy conversation is untouched.
        val healthy = db.conversationDao().findById(2)!!
        assertThat(healthy.lastMessagePreview).isEqualTo("hi Bob")
        assertThat(healthy.lastMessageAt).isEqualTo(3_000L)
    }

    @Test
    fun refreshConversationPreview_ignoresReactionSentinels() = runBlocking {
        db.messageDao().insert(message(1, 1_000L, body = "real message"))
        db.messageDao().insert(message(2, 2_000L, body = "")) // reaction sentinel (newest)
        setConvPreview(1_000L, "real message")

        db.messageDao().refreshConversationPreview(1)

        // The sentinel must not become the preview — the real message stays.
        val conv = db.conversationDao().findById(1)!!
        assertThat(conv.lastMessagePreview).isEqualTo("real message")
    }

    @Test
    fun reactionSentinels_areExcludedFromBothWindowAndStats() = runBlocking {
        seed(10)
        db.messageDao().insert(message(99, 2_000L, body = ""))

        val window = db.messageDao().observeWindowForConversation(1, 200).first()
        val stats = db.messageDao().observeStatsForConversation(1).first()

        assertThat(window.map { it.id }).doesNotContain(99L)
        assertThat(window).hasSize(10)
        assertThat(stats.total).isEqualTo(10)
    }
}
