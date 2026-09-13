package com.filestech.sms.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.data.local.db.dao.MessageSearchRow
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageSearchHit
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v1.28.8 (issue #17) — la recherche dans le texte des messages, sur une vraie base Room.
 *
 * Chaque garde a son test, pour qu'un contrôle négatif les fasse tomber un par un : le texte seul
 * (pas les numéros), le coffre (qu'une session leurre ne doit jamais voir), les lignes de service,
 * le filtre des archives. La requête reçue est déjà échappée par `escapeFtsQuery` (`cafe*`), comme
 * en production.
 *
 * Les tests de RÉÉMISSION s'abonnent d'abord, reçoivent la première émission, puis seulement
 * provoquent le changement : un abonnement tardif lirait directement le nouvel état et passerait
 * sans rien prouver de l'invalidation.
 */
@RunWith(AndroidJUnit4::class)
class MessageSearchDaoTest {

    private lateinit var db: AppDatabase

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val CONV_NORMALE = 1L
        const val CONV_COFFRE = 2L
        const val CONV_ARCHIVEE = 3L
        const val TIMEOUT_MS = 5_000L
    }

    // ⚠️ Corps de BLOC, pas d'expression : JUnit4 exige des méthodes `void`.
    @Before
    fun setUp() { runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.conversationDao().insert(conversation(CONV_NORMALE, "+33611111111"))
        db.conversationDao().insert(conversation(CONV_COFFRE, "+33622222222", inVault = true))
        db.conversationDao().insert(conversation(CONV_ARCHIVEE, "+33633333333", archived = true))

        db.messageDao().insert(message(10L, CONV_NORMALE, "On se retrouve au Café de la gare ?", date = 1_000L))
        db.messageDao().insert(message(11L, CONV_NORMALE, "Le café était fermé", date = 3_000L))
        db.messageDao().insert(message(20L, CONV_COFFRE, "Café secret du coffre", date = 2_000L))
        db.messageDao().insert(message(30L, CONV_ARCHIVEE, "Café archivé", date = 4_000L))
        db.messageDao().insert(message(40L, CONV_NORMALE, "cafe", date = 5_000L, hidden = true))
        db.messageDao().insert(message(50L, CONV_NORMALE, "Rien à voir", date = 6_000L))
    } }

    @After
    fun tearDown() = db.close()

    private fun conversation(id: Long, address: String, inVault: Boolean = false, archived: Boolean = false) =
        ConversationEntity(
            id = id,
            threadId = id,
            addressesCsv = address,
            displayName = null,
            lastMessageAt = 0L,
            lastMessagePreview = null,
            archived = archived,
            inVault = inVault,
        )

    /** L'adresse de chaque message vaut `+3360000000<conversation>` : un numéro qu'aucun corps ne contient. */
    private fun message(id: Long, convId: Long, body: String, date: Long, hidden: Boolean = false) =
        MessageEntity(
            id = id,
            conversationId = convId,
            telephonyUri = "content://sms/$id",
            address = "+3360000000$convId",
            body = body,
            type = MessageType.SMS,
            direction = MessageDirection.INCOMING,
            date = date,
            dateSent = null,
            read = true,
            starred = false,
            status = MessageStatus.RECEIVED,
            hidden = hidden,
        )

    private fun chercher(query: String, archivedOnly: Boolean = false) = runBlocking {
        db.messageDao().observeSearch(query, archivedOnly).first()
    }

    /**
     * S'abonne, rend la première émission, exécute [changement], puis rend l'émission SUIVANTE.
     * Un flux qui ne se réémettrait pas fait échouer le test par dépassement du délai.
     */
    private fun emissionApres(
        query: String,
        archivedOnly: Boolean,
        changement: suspend () -> Unit,
    ): Pair<List<MessageSearchRow>, List<MessageSearchRow>> = runBlocking {
        val emissions = Channel<List<MessageSearchRow>>(Channel.UNLIMITED)
        val abonnement = launch(Dispatchers.Default) {
            db.messageDao().observeSearch(query, archivedOnly).collect { emissions.send(it) }
        }
        try {
            val avant = withTimeout(TIMEOUT_MS) { emissions.receive() }
            changement()
            val apres = withTimeout(TIMEOUT_MS) { emissions.receive() }
            avant to apres
        } finally {
            abonnement.cancel()
        }
    }

    @Test
    fun trouveSansAccentNiCasse_duPlusRecentAuPlusAncien() {
        assertThat(chercher("cafe*").map { it.id }).containsExactly(30L, 11L, 10L).inOrder()
    }

    @Test
    fun leTexteSeulement_jamaisLeNumero() {
        // Avant le ciblage de `body`, ce préfixe du numéro remontait tous les messages.
        assertThat(chercher("33600*")).isEmpty()
    }

    @Test
    fun jamaisUnMessageDuCoffre() {
        assertThat(chercher("secret*")).isEmpty()
        assertThat(chercher("cafe*").map { it.id }).doesNotContain(20L)
    }

    @Test
    fun jamaisUneLigneDeService() {
        assertThat(chercher("cafe*").map { it.id }).doesNotContain(40L)
    }

    @Test
    fun lEcranDesArchivesNeChercheQueDansLesArchivees() {
        assertThat(chercher("cafe*", archivedOnly = true).map { it.id }).containsExactly(30L)
    }

    @Test
    fun lExtraitEncadreLePassageTrouve() {
        val extrait = chercher("cafe*").single { it.id == 11L }.excerpt

        assertThat(extrait).contains("${MessageSearchHit.MARK_START}café${MessageSearchHit.MARK_END}")
    }

    @Test
    fun unMessageSupprimeQuitteLesResultats() {
        val (avant, apres) = emissionApres("cafe*", archivedOnly = false) { db.messageDao().delete(11L) }

        assertThat(avant.map { it.id }).contains(11L)
        assertThat(apres.map { it.id }).containsExactly(30L, 10L).inOrder()
    }

    @Test
    fun uneConversationMiseAuCoffrePendantLaRechercheQuitteLesResultats() {
        val (avant, apres) = emissionApres("cafe*", archivedOnly = false) {
            db.conversationDao().setInVault(CONV_NORMALE, true)
        }

        assertThat(avant.map { it.id }).containsAtLeast(11L, 10L)
        assertThat(apres.map { it.id }).containsExactly(30L)
    }

    @Test
    fun uneConversationDesarchiveeQuitteLaRechercheDesArchives() {
        val (avant, apres) = emissionApres("cafe*", archivedOnly = true) {
            db.conversationDao().setArchived(CONV_ARCHIVEE, false)
        }

        assertThat(avant.map { it.id }).containsExactly(30L)
        assertThat(apres).isEmpty()
    }

    @Test
    fun lesConversationsDesResultatsExcluentLeCoffre() { runBlocking {
        val ids = db.conversationDao()
            .findOutsideVaultByIds(listOf(CONV_NORMALE, CONV_COFFRE, CONV_ARCHIVEE))
            .map { it.id }

        assertThat(ids).containsExactly(CONV_NORMALE, CONV_ARCHIVEE)
    } }
}
