package com.filestech.sms.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.data.mms.PdusEnAttente
import com.filestech.sms.data.sms.SystemCopyEraser
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.filestech.sms.domain.repository.ConversationDeleteResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * v1.28.9 (F17) — **le PDU gardé d'un MMS part avec son message.**
 *
 * Le PDU porte le message ENTIER, en clair, et la reprise le rejoue : laissé derrière une suppression, il
 * ressusciterait le message — la clé ne trouvant plus de ligne, la reprise l'écrirait à nouveau. Les PDU
 * sont de vrais fichiers dans le vrai dossier du téléchargeur, nommés par [PdusEnAttente.nom] ; le refus
 * d'effacement est réel (dossier en 0500), mesuré avant d'être attendu.
 *
 * Chaque test « conservé » a son contrôle positif — l'obstacle levé, le même geste aboutit — et un PDU
 * d'une AUTRE clé sert de témoin : un effacement trop large passerait sinon inaperçu.
 */
@RunWith(AndroidJUnit4::class)
class PdusGardesTest {

    private lateinit var db: AppDatabase

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val dossier: File
        get() = PdusEnAttente(context).dossier

    private val crees = mutableListOf<File>()

    private object ToutSEfface : SystemCopyEraser {
        override fun erase(message: MessageEntity): Boolean = true
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dossier.mkdirs()
    }

    @After
    fun tearDown() {
        db.close()
        android.system.Os.chmod(dossier.path, 0b111_000_000) // 0700, si un test l'a fermé
        crees.forEach { it.delete() }
    }

    @Test
    fun supprimerLaConversationEffaceLesPduGardesDeSesMms(): Unit = runBlocking {
        conversation(ALICE, inVault = false)
        val cle = cle("alice")
        message(ALICE, cle)
        val pdu = pdu(cle)
        val temoin = pdu(cle("temoin"))

        val resultat = eraserAvec().supprimer(ALICE)

        assertThat(resultat).isEqualTo(ConversationDeleteResult.DELETED)
        assertThat(pdu.exists()).isFalse()
        assertThat(temoin.exists()).isTrue()
    }

    @Test
    fun unPduQuiResisteGardeLaConversationDuCoffre(): Unit = runBlocking {
        conversation(COFFRE, inVault = true)
        val cle = cle("coffre")
        message(COFFRE, cle)
        val pdu = pdu(cle)
        android.system.Os.chmod(dossier.path, 0b101_000_000) // 0500
        try {
            // Précondition mesurée, pas supposée : le refus est réel. `delete()` rend `false` sans lever
            // (`createNewFile()`, lui, lève `IOException` dans un dossier fermé — vu sur l'émulateur).
            assertThat(pdu.delete()).isFalse()

            val refuse = eraserAvec().supprimer(COFFRE)

            assertThat(refuse).isEqualTo(ConversationDeleteResult.KEPT_LOCAL_FAILURE)
            assertThat(db.conversationDao().idsInVault()).containsExactly(COFFRE)
            assertThat(pdu.exists()).isTrue()
        } finally {
            android.system.Os.chmod(dossier.path, 0b111_000_000) // 0700
        }

        val reprise = eraserAvec().supprimer(COFFRE)

        assertThat(reprise).isEqualTo(ConversationDeleteResult.DELETED)
        assertThat(pdu.exists()).isFalse()
    }

    /**
     * Relecture GPT 5.2 du code F17 (constat 2) — **le jumeau ordinaire.** `eraseMessage` s'arrêtait déjà quand
     * le PDU résiste ; la suppression d'une conversation hors coffre, elle, supprimait les lignes et laissait
     * le PDU, que la reprise rejouait ensuite sans plus trouver la clé : la conversation supprimée revenait.
     */
    @Test
    fun unPduQuiResisteGardeAussiUneConversationOrdinaire(): Unit = runBlocking {
        conversation(ALICE, inVault = false)
        val cle = cle("ordinaire")
        message(ALICE, cle)
        val pdu = pdu(cle)
        android.system.Os.chmod(dossier.path, 0b101_000_000) // 0500
        try {
            assertThat(pdu.delete()).isFalse()

            val refuse = eraserAvec().supprimer(ALICE)

            assertThat(refuse).isEqualTo(ConversationDeleteResult.KEPT_LOCAL_FAILURE)
            assertThat(db.conversationDao().findById(ALICE)).isNotNull()
            assertThat(pdu.exists()).isTrue()
        } finally {
            android.system.Os.chmod(dossier.path, 0b111_000_000) // 0700
        }

        val reprise = eraserAvec().supprimer(ALICE)

        assertThat(reprise).isEqualTo(ConversationDeleteResult.DELETED)
        assertThat(pdu.exists()).isFalse()
    }

    @Test
    fun supprimerUnMessageSArreteSiSonPduResiste(): Unit = runBlocking {
        conversation(ALICE, inVault = false)
        val cle = cle("message")
        val cible = message(ALICE, cle)
        val pdu = pdu(cle)
        android.system.Os.chmod(dossier.path, 0b101_000_000) // 0500
        try {
            eraserAvec().eraseMessage(cible)

            assertThat(db.messageDao().findById(cible)).isNotNull()
            assertThat(pdu.exists()).isTrue()
        } finally {
            android.system.Os.chmod(dossier.path, 0b111_000_000) // 0700
        }

        eraserAvec().eraseMessage(cible)

        assertThat(db.messageDao().findById(cible)).isNull()
        assertThat(pdu.exists()).isFalse()
    }

    @Test
    fun laRetentionEffaceLesPduDesMessagesPurges(): Unit = runBlocking {
        conversation(ALICE, inVault = false)
        val ancienne = cle("ancienne")
        val recente = cle("recente")
        message(ALICE, ancienne, date = ANCIEN)
        message(ALICE, recente)
        val pduAncien = pdu(ancienne)
        val pduRecent = pdu(recente)

        val n = eraserAvec().purgeHistory(cutoff = System.currentTimeMillis() - TRENTE_JOURS)

        assertThat(n).isEqualTo(1)
        assertThat(pduAncien.exists()).isFalse()
        assertThat(pduRecent.exists()).isTrue()
    }

    /** Un MMS écrit pendant le balayage part avec la conversation ordinaire : son PDU doit le suivre. */
    @Test
    fun lePduDUnMmsArrivePendantLaSuppressionLeSuit(): Unit = runBlocking {
        conversation(ALICE, inVault = false)
        message(ALICE, cle("premier"))
        val tardive = cle("tardif")
        val pduTardif = pdu(tardive)
        val intrus = object : SystemCopyEraser {
            var fait = false
            override fun erase(message: MessageEntity): Boolean {
                if (!fait) {
                    fait = true
                    runBlocking { message(ALICE, tardive) }
                }
                return true
            }
        }

        val resultat = eraserAvec(intrus).supprimer(ALICE)

        assertThat(intrus.fait).isTrue()
        assertThat(resultat).isEqualTo(ConversationDeleteResult.DELETED)
        assertThat(pduTardif.exists()).isFalse()
    }

    /**
     * Contrôle négatif I10 du 2026-09-14 — le test précédent insère son MMS pendant le balayage système, donc
     * AVANT la lecture des clés : l'effacement préalable emporte son PDU, et l'effacement après validation
     * n'était jamais atteint — la mutation qui le retire laissait le test vert. Ici le MMS arrive APRÈS la
     * première lecture des clés et avant la transaction finale : seul l'effacement tardif peut emporter son PDU.
     */
    @Test
    fun lePduDUnMmsArriveApresLaLectureDesClesLeSuit(): Unit = runBlocking {
        conversation(ALICE, inVault = false)
        message(ALICE, cle("premier"))
        val tardive = cle("apres-lecture")
        val pduTardif = pdu(tardive)
        val dao = ApresLaLectureDesCles(db.messageDao()) { message(ALICE, tardive) }

        val resultat = eraserAvec(messageDao = dao).supprimer(ALICE)

        assertThat(dao.fait).isTrue()
        assertThat(resultat).isEqualTo(ConversationDeleteResult.DELETED)
        assertThat(pduTardif.exists()).isFalse()
    }

    /** Le jumeau de la rétention : un MMS entré dans la clause après la lecture des clés part, et son PDU le suit. */
    @Test
    fun laRetentionEffaceLePduDUnMmsEntreApresLaLectureDesCles(): Unit = runBlocking {
        conversation(ALICE, inVault = false)
        message(ALICE, cle("ancien"), date = ANCIEN)
        val tardive = cle("ancien-tardif")
        val pduTardif = pdu(tardive)
        val dao = ApresLaLectureDesCles(db.messageDao()) { message(ALICE, tardive, date = ANCIEN) }

        val n = eraserAvec(messageDao = dao).purgeHistory(cutoff = System.currentTimeMillis() - TRENTE_JOURS)

        assertThat(dao.fait).isTrue()
        assertThat(n).isEqualTo(2)
        assertThat(pduTardif.exists()).isFalse()
    }

    // ───── Outillage ─────

    /**
     * Écrit un MMS juste après la PREMIÈRE lecture des clés de transaction : la fenêtre entre l'effacement
     * préalable des PDU et la transaction finale, que seule la seconde lecture couvre.
     */
    private class ApresLaLectureDesCles(
        private val d: com.filestech.sms.data.local.db.dao.MessageDao,
        private val tardif: suspend () -> Unit,
    ) : com.filestech.sms.data.local.db.dao.MessageDao by d {
        var fait = false

        override suspend fun findTransactionKeysForConversation(conversationId: Long): List<String> =
            d.findTransactionKeysForConversation(conversationId).also { ecrireUneFois() }

        override suspend fun findTransactionKeysOlderThan(olderThan: Long): List<String> =
            d.findTransactionKeysOlderThan(olderThan).also { ecrireUneFois() }

        private suspend fun ecrireUneFois() {
            if (!fait) {
                fait = true
                tardif()
            }
        }
    }

    private fun cle(graine: String): String = PdusEnAttente.cle(graine, "http://mmsc.example/$graine", 1)!!

    private fun pdu(cle: String): File =
        File(dossier, PdusEnAttente.nom(System.currentTimeMillis(), "0badc0de", cle, 1))
            .apply { writeBytes(ByteArray(32)) }
            .also { crees += it }

    private fun eraserAvec(
        systemCopy: SystemCopyEraser = ToutSEfface,
        messageDao: com.filestech.sms.data.local.db.dao.MessageDao = db.messageDao(),
    ) = ConversationEraser(
        db,
        db.conversationDao(),
        messageDao,
        systemCopy,
        db.scheduledMessageDao(),
        object : com.filestech.sms.domain.scheduler.ScheduledMessageScheduler {
            override fun scheduleAt(scheduledMessageId: Long, epochMillis: Long) = Unit
            override fun cancel(scheduledMessageId: Long) = Unit
        },
        db.attachmentDao(),
        FichiersDePiecesJointes(db.attachmentDao(), db.scheduledMessageDao(), context),
        com.filestech.sms.security.VaultPurgeBarrier(),
        AnnulateurDeNotificationsEspion(),
    )

    private suspend fun conversation(id: Long, inVault: Boolean) {
        db.conversationDao().insert(
            ConversationEntity(
                id = id,
                threadId = id,
                addressesCsv = "+3360000${id.toString().padStart(4, '0')}",
                displayName = null,
                lastMessagePreview = "",
                lastMessageAt = 0L,
                unreadCount = 0,
                inVault = inVault,
            ),
        )
    }

    private suspend fun message(conversationId: Long, cle: String, date: Long = System.currentTimeMillis()): Long =
        db.messageDao().insert(
            MessageEntity(
                conversationId = conversationId,
                telephonyUri = null,
                address = "+3360000${conversationId.toString().padStart(4, '0')}",
                body = "",
                type = MessageType.MMS,
                direction = MessageDirection.INCOMING,
                date = date,
                dateSent = date,
                read = true,
                starred = false,
                status = MessageStatus.RECEIVED,
                errorCode = null,
                subId = 1,
                scheduledAt = null,
                attachmentsCount = 0,
                mmsTransactionKey = cle,
            ),
        )

    private companion object {
        const val ALICE = 30L
        const val COFFRE = 31L
        const val ANCIEN = 1_700_000_000_000L
        const val TRENTE_JOURS = 30L * 24 * 60 * 60 * 1000
    }
}
