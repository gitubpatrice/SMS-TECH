package com.filestech.sms.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.data.sms.PhoneIdentity
import com.filestech.sms.data.sms.PhoneNumberWireFormatter
import com.filestech.sms.data.sms.TelephonyReader
import com.filestech.sms.domain.model.Contact
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.ContactRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 2026-11-06 (courriel d'un utilisateur Visible/Verizon) — **un fil de groupe du fournisseur devient la
 * conversation de son groupe**, sur un vrai Room : à l'import, et par la réparation des imports d'avant la 1.30.0.
 *
 * Le défaut : l'import ouvrait la conversation d'un fil sur la seule adresse de sa première ligne, et la rapprochait
 * de la 1-à-1 existante de cette personne. Le groupe se mêlait à ses échanges privés ; une réponse n'allait qu'à elle.
 *
 * Contrôle négatif : [sansLesFilsLimportRetombeSurLaPremiereAdresse] rejoue l'ancien comportement — sans la liste
 * des fils de groupe, le même import verse le groupe dans la 1-à-1 de Bob. Les tests qui attendent « rien ne bouge »
 * ont leur contrôle positif dans [unFilDeGroupeVerseDansUneUnAUnEstRangeDansSonGroupe].
 */
@RunWith(AndroidJUnit4::class)
class RegroupementFilsDeGroupeTest {

    private companion object {
        const val ALICE = "+15551230001"
        const val BOB = "+15551230002"
        const val FIL_GROUPE = 50L
        const val FIL_BOB = 12L
    }

    private lateinit var db: AppDatabase
    private lateinit var mirror: ConversationMirror
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val membres = listOf(ALICE, BOB)

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private object SansContacts : ContactRepository {
        override suspend fun lookupByPhone(rawPhone: String): Contact? = null
        override suspend fun listAll(): List<Contact> = emptyList()
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        mirror = ConversationMirror(
            db,
            db.conversationDao(),
            db.messageDao(),
            db.scheduledMessageDao(),
            db.attachmentDao(),
            SansContacts,
            PhoneIdentity(PhoneNumberWireFormatter(context, SettingsRepository(context, portee))),
            AnnulateurDeNotificationsEspion(),
            Dispatchers.IO,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- Réparation des imports d'avant la 1.30.0 ----

    @Test
    fun unFilDeGroupeVerseDansUneUnAUnEstRangeDansSonGroupe(): Unit = runBlocking {
        val bob = conversation(BOB, threadId = FIL_GROUPE)
        message(bob, "content://sms/1", BOB, "prive", date = 1_000L, read = true)
        message(bob, "content://mms/7", ALICE, "du groupe", date = 2_000L, read = false)
        message(bob, "content://mms/8", BOB, "envoye au groupe", date = 3_000L, direction = MessageDirection.OUTGOING)

        val deplaces = mirror.regrouperFil(FIL_GROUPE, membres, listOf("content://mms/7", "content://mms/8"))

        assertThat(deplaces).isEqualTo(2)
        val groupe = checkNotNull(db.conversationDao().findByThreadId(FIL_GROUPE))
        assertThat(PhoneAddress.list(groupe.addressesCsv).map { it.raw }).containsExactly(ALICE, BOB)
        assertThat(conversationDe("content://mms/7")).isEqualTo(groupe.id)
        assertThat(conversationDe("content://mms/8")).isEqualTo(groupe.id)
        assertThat(conversationDe("content://sms/1")).isEqualTo(bob)
        assertThat(groupe.lastMessageAt).isEqualTo(3_000L)
        assertThat(groupe.lastMessagePreview).isEqualTo("envoye au groupe")
        assertThat(groupe.unreadCount).isEqualTo(1)
        val resteBob = checkNotNull(db.conversationDao().findById(bob))
        assertThat(resteBob.threadId).isNull()
        assertThat(resteBob.lastMessageAt).isEqualTo(1_000L)
        assertThat(resteBob.lastMessagePreview).isEqualTo("prive")
        assertThat(resteBob.unreadCount).isEqualTo(0)
    }

    @Test
    fun uneSecondePasseNeDeplaceRien(): Unit = runBlocking {
        val bob = conversation(BOB, threadId = FIL_GROUPE)
        message(bob, "content://sms/1", BOB, "prive", date = 1_000L)
        message(bob, "content://mms/7", ALICE, "du groupe", date = 2_000L)
        mirror.regrouperFil(FIL_GROUPE, membres, listOf("content://mms/7"))

        assertThat(mirror.regrouperFil(FIL_GROUPE, membres, listOf("content://mms/7"))).isEqualTo(0)
        assertThat(db.conversationDao().snapshotGroupConversations()).hasSize(1)
    }

    @Test
    fun uneConversationQuiNeTenaitQueLeGroupeDisparait(): Unit = runBlocking {
        val bob = conversation(BOB, threadId = FIL_GROUPE)
        message(bob, "content://mms/7", ALICE, "du groupe", date = 2_000L)

        mirror.regrouperFil(FIL_GROUPE, membres, listOf("content://mms/7"))

        assertThat(db.conversationDao().findById(bob)).isNull()
    }

    @Test
    fun uneConversationAvecBrouillonEstGardee(): Unit = runBlocking {
        val bob = conversation(BOB, threadId = FIL_GROUPE, brouillon = "pas encore envoye")
        message(bob, "content://mms/7", ALICE, "du groupe", date = 2_000L)

        mirror.regrouperFil(FIL_GROUPE, membres, listOf("content://mms/7"))

        assertThat(db.conversationDao().findById(bob)?.draft).isEqualTo("pas encore envoye")
    }

    @Test
    fun rienNeSortDuCoffre(): Unit = runBlocking {
        val bob = conversation(BOB, threadId = FIL_GROUPE, auCoffre = true)
        message(bob, "content://mms/7", ALICE, "du groupe", date = 2_000L)

        assertThat(mirror.regrouperFil(FIL_GROUPE, membres, listOf("content://mms/7"))).isEqualTo(0)

        assertThat(conversationDe("content://mms/7")).isEqualTo(bob)
        assertThat(db.conversationDao().findById(bob)?.threadId).isEqualTo(FIL_GROUPE)
        // Aucune coquille de groupe créée pour rien.
        assertThat(db.conversationDao().snapshotGroupConversations()).isEmpty()
    }

    @Test
    fun unGroupeExistantEcritAutrementEstRetrouve(): Unit = runBlocking {
        val existant = conversation("$BOB;$ALICE", threadId = null)
        val bob = conversation(BOB, threadId = FIL_GROUPE)
        message(bob, "content://mms/7", ALICE, "du groupe", date = 2_000L)

        mirror.regrouperFil(FIL_GROUPE, membres, listOf("content://mms/7"))

        assertThat(conversationDe("content://mms/7")).isEqualTo(existant)
        assertThat(db.conversationDao().findById(existant)?.threadId).isEqualTo(FIL_GROUPE)
        assertThat(db.conversationDao().snapshotGroupConversations()).hasSize(1)
    }

    // ---- Import ----

    @Test
    fun limportRangeUnFilDeGroupeDansSonGroupe(): Unit = runBlocking {
        val bob = conversation(BOB, threadId = FIL_BOB)
        message(bob, "content://sms/1", BOB, "prive", date = 1_000L)

        mirror.bulkImportMmsFromTelephony(lignesDuGroupe(), mapOf(FIL_GROUPE to membres))

        val groupe = checkNotNull(db.conversationDao().findByThreadId(FIL_GROUPE))
        assertThat(conversationDe("content://mms/7")).isEqualTo(groupe.id)
        assertThat(conversationDe("content://mms/8")).isEqualTo(groupe.id)
        assertThat(db.conversationDao().findById(bob)?.threadId).isEqualTo(FIL_BOB)
        assertThat(conversationDe("content://sms/1")).isEqualTo(bob)
    }

    /** Contrôle négatif : l'ancien comportement, que le test précédent doit avoir corrigé. */
    @Test
    fun sansLesFilsLimportRetombeSurLaPremiereAdresse(): Unit = runBlocking {
        val bob = conversation(BOB, threadId = FIL_BOB)
        message(bob, "content://sms/1", BOB, "prive", date = 1_000L)

        mirror.bulkImportMmsFromTelephony(lignesDuGroupe(), emptyMap())

        assertThat(conversationDe("content://mms/7")).isEqualTo(bob)
        assertThat(db.conversationDao().snapshotGroupConversations()).isEmpty()
    }

    @Test
    fun uneUnAUnQuiTenaitLeFilDuGroupeLeRend(): Unit = runBlocking {
        val bob = conversation(BOB, threadId = FIL_GROUPE)

        mirror.bulkImportMmsFromTelephony(lignesDuGroupe(), mapOf(FIL_GROUPE to membres))

        val groupe = checkNotNull(db.conversationDao().findByThreadId(FIL_GROUPE))
        assertThat(groupe.id).isNotEqualTo(bob)
        assertThat(db.conversationDao().findById(bob)?.threadId).isNull()
    }

    /** Le jumeau SMS : `bulkImportFromTelephony` porte le `thread_id` système dans `conversationId`. */
    @Test
    fun limportDesSmsRangeAussiUnFilDeGroupe(): Unit = runBlocking {
        val sms = MessageEntity(
            conversationId = FIL_GROUPE,
            telephonyUri = "content://sms/5",
            address = ALICE,
            body = "sms du groupe",
            type = MessageType.SMS,
            direction = MessageDirection.OUTGOING,
            date = 4_000L,
            dateSent = 4_000L,
            status = MessageStatus.SENT,
        )

        mirror.bulkImportFromTelephony(listOf(sms), mapOf(FIL_GROUPE to membres))

        assertThat(conversationDe("content://sms/5")).isEqualTo(db.conversationDao().findByThreadId(FIL_GROUPE)?.id)
        assertThat(db.conversationDao().snapshotOneToOneConversations()).isEmpty()
    }

    // ---- Outils ----

    private fun lignesDuGroupe() = listOf(
        ligneMms(8, BOB, MessageDirection.OUTGOING, dateMs = 3_000L),
        ligneMms(7, ALICE, MessageDirection.INCOMING, dateMs = 2_000L),
    )

    private fun ligneMms(id: Long, adresse: String, direction: MessageDirection, dateMs: Long) =
        TelephonyReader.MmsImportRow(
            telephonyId = id,
            threadId = FIL_GROUPE,
            dateMs = dateMs,
            read = true,
            direction = direction,
            status = if (direction == MessageDirection.INCOMING) MessageStatus.RECEIVED else MessageStatus.SENT,
            address = adresse,
            subId = null,
            textBody = "mms $id",
            attachments = emptyList(),
        )

    private suspend fun conversation(
        csv: String,
        threadId: Long?,
        auCoffre: Boolean = false,
        brouillon: String? = null,
    ): Long = db.conversationDao().insert(
        ConversationEntity(
            threadId = threadId,
            addressesCsv = csv,
            displayName = null,
            lastMessageAt = 0L,
            lastMessagePreview = null,
            inVault = auCoffre,
            draft = brouillon,
        ),
    )

    private suspend fun message(
        conversationId: Long,
        uri: String,
        adresse: String,
        texte: String,
        date: Long,
        read: Boolean = true,
        direction: MessageDirection = MessageDirection.INCOMING,
    ) {
        db.messageDao().insert(
            MessageEntity(
                conversationId = conversationId,
                telephonyUri = uri,
                address = adresse,
                body = texte,
                type = if (uri.startsWith("content://mms/")) MessageType.MMS else MessageType.SMS,
                direction = direction,
                date = date,
                dateSent = date,
                read = read,
                status = if (direction == MessageDirection.INCOMING) MessageStatus.RECEIVED else MessageStatus.SENT,
            ),
        )
    }

    private suspend fun conversationDe(uri: String): Long? = db.messageDao().findByTelephonyUri(uri)?.conversationId
}
