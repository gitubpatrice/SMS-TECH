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
import com.filestech.sms.domain.model.SendErrorCode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v1.28.3 (F23) — **l'accusé tardif d'une tentative périmée condamnait le message.**
 *
 * La promotion monotone posée en v1.26.1 protège les parties d'un même envoi les unes des
 * autres : un statut ne peut que progresser sur l'échelle `PENDING(0) < SENT(1) < DELIVERED(2)
 * < FAILED(3)`. Elle ne protégeait rien entre deux TENTATIVES, parce que la relance rétrograde
 * délibérément la ligne en `PENDING` — sans quoi la règle monotone bloquerait la relance
 * elle-même. L'accusé en retard de la tentative précédente retrouvait donc une ligne au bas de
 * l'échelle et s'y appliquait comme s'il était le sien.
 *
 * Le pire ordonnancement n'est pas rare : on relance depuis une zone sans réseau, l'accusé
 * d'échec de la tentative d'avant arrive avec une minute de retard, écrit `FAILED` — et le
 * `SENT` de la tentative en cours, arrivé après, ne peut **plus jamais** le promouvoir, `FAILED`
 * étant le sommet de l'échelle. Bulle rouge définitive sur un message bel et bien reçu, et
 * relance proposée sur un message déjà délivré.
 *
 * Ces tests mesurent la règle sur du vrai SQLite, pas sur une intention : c'est la requête qui
 * arbitre, et c'est elle qu'on exerce.
 */
@RunWith(AndroidJUnit4::class)
class SendAttemptStatusTest {

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
                    addressesCsv = "+33612345678",
                    displayName = "Alice",
                    lastMessageAt = 0,
                    lastMessagePreview = null,
                ),
            )
        }
    }

    @After
    fun tearDown() = db.close()

    private fun seedOutgoing(): Long = runBlocking {
        db.messageDao().insert(
            MessageEntity(
                conversationId = 1,
                telephonyUri = "content://sms/1",
                address = "+33612345678",
                body = "Bonjour",
                type = MessageType.SMS,
                direction = MessageDirection.OUTGOING,
                date = 1_700_000_000_000L,
                dateSent = null,
                status = MessageStatus.PENDING,
            ),
        )
    }

    private suspend fun statusOf(id: Long) = db.messageDao().findById(id)?.status

    /** Le scénario complet, dans l'ordre exact qui produisait la bulle rouge définitive. */
    @Test
    fun unAccuseDEchecEnRetardNeCondamnePlusLaTentativeSuivante(): Unit = runBlocking {
        val dao = db.messageDao()
        val id = seedOutgoing()

        // Tentative 0 : rien ne revient, le chien de garde la marque en échec.
        dao.promoteStatusMonotonic(id, MessageStatus.FAILED, 3, SendErrorCode.WATCHDOG_TIMEOUT, 0)
        assertThat(statusOf(id)).isEqualTo(MessageStatus.FAILED)

        // L'utilisateur relance : nouvelle tentative, ligne ramenée en attente.
        dao.openNextSendAttempt(id)
        assertThat(dao.sendAttemptOf(id)).isEqualTo(1)
        assertThat(statusOf(id)).isEqualTo(MessageStatus.PENDING)
        assertThat(dao.findById(id)?.errorCode).isNull()

        // L'accusé d'ÉCHEC de la tentative 0 arrive enfin. Il ne doit rien écrire.
        dao.promoteStatusMonotonic(id, MessageStatus.FAILED, 3, errorCode = 4, attempt = 0)
        assertThat(statusOf(id)).isEqualTo(MessageStatus.PENDING)

        // Puis l'accusé de la tentative 1, le vrai : il passe.
        dao.promoteStatusMonotonic(id, MessageStatus.SENT, 1, null, 1)
        assertThat(statusOf(id)).isEqualTo(MessageStatus.SENT)
    }

    /**
     * Contrôle POSITIF, sans lequel le test précédent ne prouverait rien : une requête qui
     * n'écrirait plus JAMAIS rien le passerait aussi. L'accusé de la tentative en cours doit
     * évidemment s'appliquer.
     */
    @Test
    fun laccuseDeLaTentativeEnCoursSApplique(): Unit = runBlocking {
        val dao = db.messageDao()
        val id = seedOutgoing()

        dao.promoteStatusMonotonic(id, MessageStatus.SENT, 1, null, 0)

        assertThat(statusOf(id)).isEqualTo(MessageStatus.SENT)
    }

    /**
     * Second contrôle positif : `attempt = null` veut dire « quelle que soit la tentative en
     * cours », et c'est ce dont ont besoin le chien de garde, l'échec synchrone et le suivi MMS.
     * Le filtre ne doit pas les paralyser.
     */
    @Test
    fun uneEcritureSansTentativeSAppliqueQuelQueSoitLeCompteur(): Unit = runBlocking {
        val dao = db.messageDao()
        val id = seedOutgoing()
        dao.openNextSendAttempt(id)
        dao.openNextSendAttempt(id)
        assertThat(dao.sendAttemptOf(id)).isEqualTo(2)

        dao.promoteStatusMonotonic(id, MessageStatus.FAILED, 3, SendErrorCode.WATCHDOG_TIMEOUT, null)

        assertThat(statusOf(id)).isEqualTo(MessageStatus.FAILED)
    }

    /**
     * La monotonie de la v1.26.1 doit survivre au correctif : à tentative égale, l'accusé positif
     * d'une partie ne peut toujours pas écraser l'échec d'une autre. C'est le défaut M8, qui
     * faisait croire envoyé un message tronqué.
     */
    @Test
    fun laMonotonieDeM8TientToujoursAlInterieurDuneTentative(): Unit = runBlocking {
        val dao = db.messageDao()
        val id = seedOutgoing()

        dao.promoteStatusMonotonic(id, MessageStatus.FAILED, 3, errorCode = 2, attempt = 0)
        dao.promoteStatusMonotonic(id, MessageStatus.SENT, 1, null, 0)

        assertThat(statusOf(id)).isEqualTo(MessageStatus.FAILED)
    }

    /** Une tentative POSTÉRIEURE ne s'applique pas davantage — le filtre est une égalité. */
    @Test
    fun unAccuseDuneTentativeInexistanteEstIgnore(): Unit = runBlocking {
        val dao = db.messageDao()
        val id = seedOutgoing()

        dao.promoteStatusMonotonic(id, MessageStatus.SENT, 1, null, attempt = 7)

        assertThat(statusOf(id)).isEqualTo(MessageStatus.PENDING)
    }
}
