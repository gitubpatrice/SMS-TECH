package com.filestech.sms.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.sms.PhoneIdentity
import com.filestech.sms.data.sms.PhoneNumberWireFormatter
import com.filestech.sms.domain.model.Contact
import com.filestech.sms.domain.model.MessageStatus
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
 * 2026-11-10 (issue #87) — **un SMS dont la copie système vient d'être créée est toujours écrit chez nous**,
 * sur un vrai Room.
 *
 * L'insertion est en `IGNORE` sur `UNIQUE(telephony_uri)`. Quand une ligne de chez nous portait déjà l'adresse
 * que le fournisseur venait de rendre, l'envoi recevait `-1`, mettait quand même à jour l'aperçu de la
 * conversation (message visible dans la liste, absent du fil) et partait sous l'id `-1` ; la réception rendait
 * l'id de l'ANCIENNE ligne, et le SMS reçu n'était écrit nulle part chez nous.
 *
 * Les deux cas d'un conflit, que [ConversationMirror] distingue par le sens et l'heure :
 *  - un lien PÉRIMÉ (autre message) : le nouveau est écrit, l'ancien perd son lien ;
 *  - le MÊME message, déjà miroité : sa ligne est rendue, sans doublon ni double compte.
 */
@RunWith(AndroidJUnit4::class)
class EcritureSystemeConflitTest {

    private lateinit var db: AppDatabase
    private lateinit var mirror: ConversationMirror
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    @Test
    fun unEnvoiSurUneAdressePerimeeEstEcritEtLAncienneLigneDetachee(): Unit = runBlocking {
        val ancien = mirror.upsertIncomingSms(ALICE, "ancien", date = 1_000L, telephonyUri = URI)

        val nouveau = envoyer("nouveau", date = 2_000L, uri = URI)

        assertThat(nouveau).isGreaterThan(0L)
        assertThat(nouveau).isNotEqualTo(ancien)
        val ligne = db.messageDao().findById(nouveau)!!
        assertThat(ligne.body).isEqualTo("nouveau")
        assertThat(ligne.telephonyUri).isEqualTo(URI)
        // L'ancien message reste, sans le lien qui ferait effacer chez Android le message neuf.
        val ancienneLigne = db.messageDao().findById(ancien)!!
        assertThat(ancienneLigne.body).isEqualTo("ancien")
        assertThat(ancienneLigne.telephonyUri).isNull()
        // L'aperçu de la liste annonce un message qui est bien dans le fil.
        val conversation = db.conversationDao().findById(ligne.conversationId)!!
        assertThat(conversation.lastMessagePreview).isEqualTo("nouveau")
        assertThat(db.messageDao().findByConversation(ligne.conversationId).map { it.body })
            .containsExactly("ancien", "nouveau")
    }

    @Test
    fun unEnvoiDejaMiroiteRendSaLigneSansDoublon(): Unit = runBlocking {
        val premier = envoyer("bonjour", date = 2_000L, uri = URI)

        val second = envoyer("bonjour", date = 2_000L, uri = URI)

        assertThat(premier).isGreaterThan(0L)
        assertThat(second).isEqualTo(premier)
        val conversationId = db.messageDao().findById(premier)!!.conversationId
        assertThat(db.messageDao().findByConversation(conversationId)).hasSize(1)
    }

    @Test
    fun unSmsRecuSurUneAdressePerimeeEstEcrit(): Unit = runBlocking {
        val ancien = envoyer("parti il y a longtemps", date = 1_000L, uri = URI)

        val recu = mirror.upsertIncomingSms(ALICE, "reçu", date = 3_000L, telephonyUri = URI)

        assertThat(recu).isGreaterThan(0L)
        assertThat(recu).isNotEqualTo(ancien)
        val ligne = db.messageDao().findById(recu)!!
        assertThat(ligne.body).isEqualTo("reçu")
        assertThat(ligne.telephonyUri).isEqualTo(URI)
        assertThat(db.messageDao().findById(ancien)!!.telephonyUri).isNull()
        val conversation = db.conversationDao().findById(ligne.conversationId)!!
        assertThat(conversation.unreadCount).isEqualTo(1)
        assertThat(conversation.lastMessagePreview).isEqualTo("reçu")
    }

    @Test
    fun unSmsRecuDejaMiroiteNeCompteQuUneFois(): Unit = runBlocking {
        val premier = mirror.upsertIncomingSms(ALICE, "salut", date = 3_000L, telephonyUri = URI)

        val second = mirror.upsertIncomingSms(ALICE, "salut", date = 3_000L, telephonyUri = URI)

        assertThat(premier).isGreaterThan(0L)
        assertThat(second).isEqualTo(premier)
        val conversation = db.conversationDao().findById(db.messageDao().findById(premier)!!.conversationId)!!
        assertThat(conversation.unreadCount).isEqualTo(1)
    }

    private suspend fun envoyer(corps: String, date: Long, uri: String?): Long = mirror.upsertOutgoingSms(
        address = ALICE,
        body = corps,
        date = date,
        telephonyUri = uri,
        subId = null,
        initialStatus = MessageStatus.PENDING,
        replyToMessageId = null,
        localMirrorBody = null,
    )

    private companion object {
        const val ALICE = "+33612345678"
        const val URI = "content://sms/42"
    }
}
