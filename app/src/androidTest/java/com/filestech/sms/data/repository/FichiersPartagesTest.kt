package com.filestech.sms.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.CitationDePieceJointe
import com.filestech.sms.data.local.db.entity.AttachmentEntity
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.data.local.db.entity.ScheduledMessageEntity
import com.filestech.sms.data.sms.SystemCopyEraser
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * v1.28.9 (septième note d'Andrew, MR !38458, constats 1 et 3, et B5 du registre) — **un fichier de
 * pièce jointe ne part que lorsque plus aucune ligne ne le cite.**
 *
 * Les producteurs réels du partage sont reproduits tels quels : une copie durable citée par la ligne
 * de chaque destinataire, par l'écho de groupe, et par l'envoi programmé qui l'a promue. Les fichiers
 * sont RÉELLEMENT écrits sur le disque : vérifier la seule disparition d'une ligne Room est l'erreur
 * qui a laissé passer F04.
 *
 * Chaque test « le fichier reste » se termine par la suppression de la dernière citation et exige
 * que le fichier parte : un effaceur qui ne supprimerait plus rien passerait sinon tous les tests.
 */
@RunWith(AndroidJUnit4::class)
class FichiersPartagesTest {

    private lateinit var db: AppDatabase
    private lateinit var dossier: File

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private object ToutSEfface : SystemCopyEraser {
        override fun erase(message: MessageEntity): Boolean = true
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dossier = File(context.filesDir, "mms_attachments/partages-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        db.close()
        dossier.deleteRecursively()
    }

    // ───── Constat 1 : les producteurs du partage ─────

    @Test
    fun unFichierCiteParDeuxDestinatairesSurvitALaSuppressionDuPremierMessage(): Unit = runBlocking {
        conversation(ALICE, "+33600000001")
        conversation(BOB, "+33600000002")
        val photo = fichier("out-photo.jpg")
        val pourAlice = message(ALICE)
        val pourBob = message(BOB)
        citer(pourAlice, photo)
        citer(pourBob, photo)

        eraserAvec().eraseMessage(pourAlice)

        assertThat(db.messageDao().findById(pourAlice)).isNull()
        assertThat(photo.exists()).isTrue()
        assertThat(db.attachmentDao().findForMessage(pourBob).map { it.localUri }).containsExactly(photo.absolutePath)

        eraserAvec().eraseMessage(pourBob)

        assertThat(photo.exists()).isFalse()
    }

    @Test
    fun supprimerLeFilDuGroupeNeVidePasLesFilsIndividuels(): Unit = runBlocking {
        conversation(ALICE, "+33600000001")
        conversation(BOB, "+33600000002")
        conversation(GROUPE, "+33600000001;+33600000002")
        val photo = fichier("out-groupe.jpg")
        citer(message(ALICE), photo)
        citer(message(BOB), photo)
        citer(message(GROUPE), photo) // l'écho de groupe

        eraserAvec().erase(GROUPE)

        assertThat(db.conversationDao().findById(GROUPE)).isNull()
        assertThat(photo.exists()).isTrue()

        eraserAvec().erase(ALICE)
        assertThat(photo.exists()).isTrue()

        eraserAvec().erase(BOB)
        assertThat(photo.exists()).isFalse()
    }

    @Test
    fun supprimerLaConversationDUnEnvoiProgrammeGardeLeFichierQueSesMessagesCitent(): Unit = runBlocking {
        conversation(ALICE, "+33600000001")
        conversation(GROUPE, "+33600000001;+33600000002")
        val photo = fichier("out-programme.jpg")
        programmer(GROUPE, photo)
        citer(message(ALICE), photo) // la ligne que l'envoi a produite chez Alice

        eraserAvec().erase(GROUPE)

        assertThat(db.scheduledMessageDao().findForConversation(GROUPE)).isEmpty()
        assertThat(photo.exists()).isTrue()

        eraserAvec().erase(ALICE)
        assertThat(photo.exists()).isFalse()
    }

    @Test
    fun unMessageSupprimeNEffacePasLeFichierDUnEnvoiProgrammeQuiLeCite(): Unit = runBlocking {
        conversation(ALICE, "+33600000001")
        val photo = fichier("out-tentative.jpg")
        val programme = programmer(ALICE, photo)
        val tentative = message(ALICE)
        citer(tentative, photo)

        eraserAvec().eraseMessage(tentative)
        assertThat(photo.exists()).isTrue()

        val fichiers = fichiersAvec()
        assertThat(
            fichiers.effacerSiPlusCites(
                listOf(photo.absolutePath),
                FichiersDePiecesJointes.Exclusion.EnvoiProgramme(programme),
            ),
        ).isEqualTo(0)
        assertThat(photo.exists()).isFalse()
    }

    @Test
    fun lEnvoiProgrammeNEffacePasUnFichierQuUnMessageCiteEncore(): Unit = runBlocking {
        conversation(ALICE, "+33600000001")
        val photo = fichier("out-cite.jpg")
        val programme = programmer(ALICE, photo)
        citer(message(ALICE), photo)

        val echecs = fichiersAvec().effacerSiPlusCites(
            listOf(photo.absolutePath),
            FichiersDePiecesJointes.Exclusion.EnvoiProgramme(programme),
        )

        // Conservé à dessein : ce n'est pas un échec.
        assertThat(echecs).isEqualTo(0)
        assertThat(photo.exists()).isTrue()
    }

    /** Le vrai dépôt : c'est lui que l'écran « Messages programmés » appelle pour supprimer. */
    @Test
    fun supprimerUnEnvoiProgrammeGardeLeFichierQueSesTentativesCitent(): Unit = runBlocking {
        conversation(ALICE, "+33600000001")
        val photo = fichier("out-depot.jpg")
        val programme = programmer(ALICE, photo)
        val tentative = message(ALICE)
        citer(tentative, photo)

        depotProgramme().deleteWithAttachments(programme)

        assertThat(db.scheduledMessageDao().findById(programme)).isNull()
        assertThat(photo.exists()).isTrue()

        eraserAvec().eraseMessage(tentative)
        assertThat(photo.exists()).isFalse()
    }

    @Test
    fun annulerUnEnvoiProgrammeNeVideLaColonneQueSiAucunFichierNAResiste(): Unit = runBlocking {
        conversation(ALICE, "+33600000001")
        val photo = fichier("out-annule.jpg")
        val programme = programmer(ALICE, photo)

        depotProgramme(CitationsIllisibles(db.attachmentDao())).clearAttachments(programme)

        // Citations illisibles : rien d'effacé, et la ligne garde la dernière citation du fichier.
        assertThat(photo.exists()).isTrue()
        assertThat(db.scheduledMessageDao().findById(programme)?.attachmentsJson).isNotNull()

        depotProgramme().clearAttachments(programme)

        assertThat(photo.exists()).isFalse()
        assertThat(db.scheduledMessageDao().findById(programme)?.attachmentsJson).isNull()
    }

    // ───── Constat 3 : la rétention ─────

    @Test
    fun laRetentionEffaceLeFichierDUnMessagePurge(): Unit = runBlocking {
        conversation(ALICE, "+33600000001")
        val ancienne = fichier("in-ancienne.jpg")
        val purge = message(ALICE, date = ANCIEN)
        citer(purge, ancienne)

        val n = eraserAvec().purgeHistory(cutoff = System.currentTimeMillis() - TRENTE_JOURS)

        assertThat(n).isEqualTo(1)
        assertThat(db.messageDao().findById(purge)).isNull()
        assertThat(ancienne.exists()).isFalse()
    }

    @Test
    fun laRetentionGardeUnFichierQuUnMessageConserveCiteEncore(): Unit = runBlocking {
        conversation(ALICE, "+33600000001")
        conversation(GROUPE, "+33600000001;+33600000002")
        val recente = fichier("out-recente.jpg")
        val favorite = fichier("out-favorite.jpg")
        citer(message(ALICE, date = ANCIEN), recente)
        citer(message(GROUPE), recente) // l'écho, récent, survit
        citer(message(ALICE, date = ANCIEN), favorite)
        val etoile = message(ALICE, date = ANCIEN, starred = true)
        citer(etoile, favorite) // un favori n'est jamais purgé

        eraserAvec().purgeHistory(cutoff = System.currentTimeMillis() - TRENTE_JOURS)

        assertThat(recente.exists()).isTrue()
        assertThat(favorite.exists()).isTrue()

        eraserAvec().eraseMessage(etoile)
        assertThat(favorite.exists()).isFalse()
    }

    // ───── Le sens de l'échec ─────

    @Test
    fun desCitationsIllisiblesNEffacentRienEtComptentEnEchec(): Unit = runBlocking {
        conversation(ALICE, "+33600000001")
        val photo = fichier("out-illisible.jpg")
        citer(message(ALICE), photo)

        val echecs = fichiersAvec(CitationsIllisibles(db.attachmentDao()))
            .effacerSiPlusCites(listOf(photo.absolutePath))

        assertThat(echecs).isEqualTo(1)
        assertThat(photo.exists()).isTrue()
    }

    @Test
    fun desCitationsIllisiblesGardentLeParentDuCoffre(): Unit = runBlocking {
        conversation(COFFRE, "+33600000009", inVault = true)
        val photo = fichier("in-coffre.jpg")
        citer(message(COFFRE), photo)

        val refuse = eraserAvec(CitationsIllisibles(db.attachmentDao())).purgeVault()

        assertThat(refuse.localFailures).isEqualTo(1)
        assertThat(refuse.isComplete).isFalse()
        assertThat(db.conversationDao().idsInVault()).containsExactly(COFFRE)
        assertThat(photo.exists()).isTrue()

        val reprise = eraserAvec().purgeVault()

        assertThat(reprise.isComplete).isTrue()
        assertThat(photo.exists()).isFalse()
    }

    @Test
    fun uneLectureDesPiecesQuiEchoueArreteLaSuppressionDuMessage(): Unit = runBlocking {
        conversation(ALICE, "+33600000001")
        val photo = fichier("out-arret.jpg")
        val cible = message(ALICE)
        citer(cible, photo)
        val espion = object : SystemCopyEraser {
            var appels = 0
            override fun erase(message: MessageEntity): Boolean {
                appels++
                return true
            }
        }

        eraserAvec(PiecesIllisibles(db.attachmentDao()), espion).eraseMessage(cible)

        assertThat(db.messageDao().findById(cible)).isNotNull()
        assertThat(photo.exists()).isTrue()
        assertThat(espion.appels).isEqualTo(0)
    }

    // ───── B5 : une pièce jointe écrite pendant la suppression ─────

    @Test
    fun unePieceArriveePendantLaPurgeDuCoffreGardeLeParent(): Unit = runBlocking {
        conversation(COFFRE, "+33600000009", inVault = true)
        val premiere = fichier("in-premiere.jpg")
        val tardive = fichier("in-tardive.jpg")
        val porteur = message(COFFRE)
        citer(porteur, premiere)
        val intrus = PieceTardive(db.attachmentDao()) { piece(porteur, tardive) }

        val refuse = eraserAvec(intrus).purgeVault()

        assertThat(intrus.inseree).isTrue()
        assertThat(refuse.localFailures).isEqualTo(1)
        assertThat(db.conversationDao().idsInVault()).containsExactly(COFFRE)
        assertThat(tardive.exists()).isTrue()

        val reprise = eraserAvec().purgeVault()

        assertThat(reprise.isComplete).isTrue()
        assertThat(premiere.exists()).isFalse()
        assertThat(tardive.exists()).isFalse()
    }

    @Test
    fun unePieceArriveePendantUneSuppressionOrdinairePartAvecLaConversation(): Unit = runBlocking {
        conversation(ALICE, "+33600000001")
        val premiere = fichier("in-premiere-ord.jpg")
        val tardive = fichier("in-tardive-ord.jpg")
        val porteur = message(ALICE)
        citer(porteur, premiere)
        val intrus = PieceTardive(db.attachmentDao()) { piece(porteur, tardive) }

        eraserAvec(intrus).erase(ALICE)

        assertThat(intrus.inseree).isTrue()
        assertThat(db.conversationDao().findById(ALICE)).isNull()
        assertThat(premiere.exists()).isFalse()
        assertThat(tardive.exists()).isFalse()
    }

    // ───── Outillage ─────

    /** La base répond à tout, sauf à « qui cite ce fichier ». */
    private class CitationsIllisibles(d: AttachmentDao) : AttachmentDao by d {
        override suspend fun findCitations(localUris: List<String>): List<CitationDePieceJointe> =
            error("CITATIONS_ILLISIBLES")
    }

    /** La base ne sait plus lister les pièces d'un message. */
    private class PiecesIllisibles(d: AttachmentDao) : AttachmentDao by d {
        override suspend fun findForMessage(messageId: Long): List<AttachmentEntity> = error("PIECES_ILLISIBLES")
    }

    /**
     * Écrit une pièce jointe au moment où l'effaceur compte les citations : il a déjà lu les pièces
     * de la conversation, et la transaction finale n'a pas commencé — la fenêtre exacte de B5.
     */
    private class PieceTardive(
        private val d: AttachmentDao,
        private val tardive: () -> AttachmentEntity,
    ) : AttachmentDao by d {
        var inseree = false
        override suspend fun findCitations(localUris: List<String>): List<CitationDePieceJointe> {
            if (!inseree) {
                inseree = true
                d.insert(tardive())
            }
            return d.findCitations(localUris)
        }
    }

    private fun fichiersAvec(attachmentDao: AttachmentDao = db.attachmentDao()) =
        FichiersDePiecesJointes(attachmentDao, db.scheduledMessageDao(), context)

    /** Verrou et session du coffre ne servent qu'aux flux affichés, pas aux deux effacements testés. */
    private fun depotProgramme(attachmentDao: AttachmentDao = db.attachmentDao()) =
        ScheduledMessageRepositoryImpl(
            db.scheduledMessageDao(),
            io.mockk.mockk(relaxed = true),
            com.filestech.sms.security.VaultSessionState(),
            fichiersAvec(attachmentDao),
            kotlinx.coroutines.Dispatchers.IO,
        )

    /** Un seul point de construction : la signature a déjà bougé deux fois. */
    private fun eraserAvec(
        attachmentDao: AttachmentDao = db.attachmentDao(),
        systemCopy: SystemCopyEraser = ToutSEfface,
    ) = ConversationEraser(
        db,
        db.conversationDao(),
        db.messageDao(),
        systemCopy,
        db.scheduledMessageDao(),
        object : com.filestech.sms.domain.scheduler.ScheduledMessageScheduler {
            override fun scheduleAt(scheduledMessageId: Long, epochMillis: Long) = Unit
            override fun cancel(scheduledMessageId: Long) = Unit
        },
        attachmentDao,
        fichiersAvec(attachmentDao),
        com.filestech.sms.security.VaultPurgeBarrier(),
        AnnulateurDeNotificationsEspion(),
    )

    private fun fichier(nom: String): File = File(dossier, nom).apply { writeBytes(ByteArray(16)) }

    private suspend fun conversation(id: Long, adresses: String, inVault: Boolean = false) {
        db.conversationDao().insert(
            ConversationEntity(
                id = id,
                threadId = id,
                addressesCsv = adresses,
                displayName = null,
                lastMessagePreview = "",
                lastMessageAt = 0L,
                unreadCount = 0,
                inVault = inVault,
            ),
        )
    }

    private suspend fun message(
        conversationId: Long,
        date: Long = System.currentTimeMillis(),
        starred: Boolean = false,
    ): Long = db.messageDao().insert(
        MessageEntity(
            conversationId = conversationId,
            telephonyUri = null,
            address = "+33600000001",
            body = "",
            type = MessageType.MMS,
            direction = MessageDirection.OUTGOING,
            date = date,
            dateSent = null,
            read = true,
            starred = starred,
            status = MessageStatus.SENT,
            errorCode = null,
            subId = null,
            scheduledAt = null,
            attachmentsCount = 1,
        ),
    )

    private fun piece(messageId: Long, fichier: File) = AttachmentEntity(
        messageId = messageId,
        mimeType = "image/jpeg",
        fileName = fichier.name,
        sizeBytes = fichier.length(),
        localUri = fichier.absolutePath,
    )

    private suspend fun citer(messageId: Long, fichier: File) {
        db.attachmentDao().insert(piece(messageId, fichier))
    }

    private suspend fun programmer(conversationId: Long, fichier: File): Long =
        db.scheduledMessageDao().upsert(
            ScheduledMessageEntity(
                conversationId = conversationId,
                addressesCsv = "+33600000001",
                body = "",
                scheduledAt = System.currentTimeMillis() + 3_600_000L,
                subId = null,
                attachmentsJson = "image/jpeg||||${fichier.absolutePath}",
                createdAt = System.currentTimeMillis(),
            ),
        )

    private companion object {
        const val ALICE = 10L
        const val BOB = 11L
        const val GROUPE = 12L
        const val COFFRE = 13L
        const val ANCIEN = 1_700_000_000_000L
        const val TRENTE_JOURS = 30L * 24 * 60 * 60 * 1000
    }
}
