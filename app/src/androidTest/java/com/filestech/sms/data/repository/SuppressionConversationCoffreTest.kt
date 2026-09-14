package com.filestech.sms.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.CitationDePieceJointe
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.entity.AttachmentEntity
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
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
 * v1.28.9 (septième note d'Andrew, MR !38458, point 5) — **supprimer une conversation du coffre ne
 * la fait plus revenir hors du coffre.**
 *
 * Le geste « Supprimer » d'un fil du coffre appelait l'effaceur en mode ordinaire : la ligne locale
 * partait même quand la copie système résistait, avec elle le drapeau `in_vault`, et la
 * synchronisation suivante réimportait la conversation dans la liste principale, en clair.
 *
 * Le refus du fournisseur est une donnée du test ([Effaceur]), pas une propriété d'environnement :
 * aucun rôle SMS, aucun vrai message. Chaque test « conservée » a son contrôle positif — la même
 * conversation se supprime dès que l'obstacle est levé — sans quoi un `supprimer` qui ne supprime
 * plus rien passerait tout ce fichier.
 *
 * Aussi : la purge ordinaire dit désormais le fichier qui a résisté (`localeComplete`), et sous
 * `force` une pièce jointe écrite pendant la purge garde le parent au lieu de laisser un orphelin.
 */
@RunWith(AndroidJUnit4::class)
class SuppressionConversationCoffreTest {

    private lateinit var db: AppDatabase
    private lateinit var dossier: File

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Le fournisseur du système, qui coopère ou refuse ; compte les appels. */
    private class Effaceur(private val coopere: Boolean) : SystemCopyEraser {
        var appels = 0
        override fun erase(message: MessageEntity): Boolean {
            appels++
            return coopere
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dossier = File(context.filesDir, "mms_attachments/suppression-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        db.close()
        android.system.Os.chmod(dossier.path, 0b111_000_000) // 0700, si un test l'a fermé
        dossier.deleteRecursively()
    }

    @Test
    fun uneConversationDuCoffreDontLaCopieSystemeResisteEstConservee(): Unit = runBlocking {
        conversation(COFFRE, inVault = true)
        message(COFFRE)

        val resultat = eraserAvec(Effaceur(coopere = false)).supprimer(COFFRE)

        assertThat(resultat).isEqualTo(ConversationDeleteResult.KEPT_SYSTEM_COPY)
        assertThat(db.conversationDao().idsInVault()).containsExactly(COFFRE)
        assertThat(db.messageDao().findByConversation(COFFRE)).hasSize(1)

        // Contrôle positif : le fournisseur coopère, la même conversation part.
        val reprise = eraserAvec(Effaceur(coopere = true)).supprimer(COFFRE)

        assertThat(reprise).isEqualTo(ConversationDeleteResult.DELETED)
        assertThat(db.conversationDao().idsInVault()).isEmpty()
    }

    /** Hors coffre, le contrat ordinaire est intact : la ligne part même si la copie système résiste. */
    @Test
    fun horsCoffreLaConversationPartMemeSiLaCopieSystemeResiste(): Unit = runBlocking {
        conversation(ALICE, inVault = false)
        message(ALICE)

        val resultat = eraserAvec(Effaceur(coopere = false)).supprimer(ALICE)

        assertThat(resultat).isEqualTo(ConversationDeleteResult.DELETED)
        assertThat(db.conversationDao().findById(ALICE)).isNull()
    }

    @Test
    fun unFichierQuiResisteGardeLaConversationDuCoffre(): Unit = runBlocking {
        conversation(COFFRE, inVault = true)
        val photo = File(dossier, "secret.jpg").apply { writeBytes(ByteArray(16)) }
        citer(message(COFFRE), photo)
        android.system.Os.chmod(dossier.path, 0b101_000_000) // 0500
        try {
            // Précondition mesurée, pas supposée : le refus est réel.
            assertThat(photo.delete()).isFalse()

            val resultat = eraserAvec(Effaceur(coopere = true)).supprimer(COFFRE)

            assertThat(resultat).isEqualTo(ConversationDeleteResult.KEPT_LOCAL_FAILURE)
            assertThat(db.conversationDao().idsInVault()).containsExactly(COFFRE)
            assertThat(photo.exists()).isTrue()
        } finally {
            android.system.Os.chmod(dossier.path, 0b111_000_000) // 0700
        }

        val reprise = eraserAvec(Effaceur(coopere = true)).supprimer(COFFRE)

        assertThat(reprise).isEqualTo(ConversationDeleteResult.DELETED)
        assertThat(photo.exists()).isFalse()
    }

    /** Ne pas savoir si la conversation est au coffre, c'est ne pas pouvoir la protéger : rien n'est touché. */
    @Test
    fun unEtatIllisibleNeSupprimeRien(): Unit = runBlocking {
        conversation(COFFRE, inVault = true)
        message(COFFRE)
        val effaceur = Effaceur(coopere = true)

        val resultat = eraserAvec(effaceur, EtatIllisible(db.conversationDao())).supprimer(COFFRE)

        assertThat(resultat).isEqualTo(ConversationDeleteResult.KEPT_LOCAL_FAILURE)
        assertThat(effaceur.appels).isEqualTo(0)
        assertThat(db.conversationDao().idsInVault()).containsExactly(COFFRE)
    }

    /**
     * La purge totale emploie le mode ordinaire : la ligne part quoi qu'il arrive, mais un fichier
     * qui a résisté doit se DIRE, sans quoi « Supprimer toutes mes données » l'annoncerait effacé.
     */
    @Test
    fun leModeOrdinaireDitLeFichierQuiAResiste(): Unit = runBlocking {
        conversation(ALICE, inVault = false)
        val photo = File(dossier, "reste.jpg").apply { writeBytes(ByteArray(16)) }
        citer(message(ALICE), photo)
        android.system.Os.chmod(dossier.path, 0b101_000_000) // 0500
        val issue = try {
            assertThat(photo.delete()).isFalse()
            eraserAvec(Effaceur(coopere = true)).erase(ALICE, ConversationEraser.Mode.ORDINAIRE)
        } finally {
            android.system.Os.chmod(dossier.path, 0b111_000_000) // 0700
        }

        assertThat(issue.conservee).isFalse()
        assertThat(issue.localeComplete).isFalse()
        assertThat(db.conversationDao().findById(ALICE)).isNull()

        // Contrôle positif : sans obstacle, le même chemin se dit complet.
        conversation(BOB, inVault = false)
        citer(message(BOB), File(dossier, "part.jpg").apply { writeBytes(ByteArray(16)) })
        val complet = eraserAvec(Effaceur(coopere = true)).erase(BOB, ConversationEraser.Mode.ORDINAIRE)
        assertThat(complet.localeComplete).isTrue()
    }

    /**
     * Sous `force`, une pièce jointe écrite pendant la purge garde le parent : `force` ne lève que la
     * condition « copie système ». Partie avec lui, son fichier deviendrait orphelin au premier
     * effacement raté, et le coffre relu vide se dirait complet.
     */
    @Test
    fun sousForceUnePieceTardiveGardeLeParent(): Unit = runBlocking {
        conversation(COFFRE, inVault = true)
        val premiere = File(dossier, "premiere.jpg").apply { writeBytes(ByteArray(16)) }
        val tardive = File(dossier, "tardive.jpg").apply { writeBytes(ByteArray(16)) }
        val porteur = message(COFFRE)
        citer(porteur, premiere)
        val intrus = PieceTardive(db.attachmentDao()) { piece(porteur, tardive) }

        val refuse = eraserAvec(Effaceur(coopere = true), attachmentDao = intrus).purgeVault(force = true)

        assertThat(intrus.inseree).isTrue()
        assertThat(refuse.localFailures).isEqualTo(1)
        assertThat(refuse.residuSystemeSeul).isFalse()
        assertThat(db.conversationDao().idsInVault()).containsExactly(COFFRE)

        val reprise = eraserAvec(Effaceur(coopere = true)).purgeVault(force = true)

        assertThat(reprise.isComplete).isTrue()
        assertThat(premiere.exists()).isFalse()
        assertThat(tardive.exists()).isFalse()
    }

    // ───── Outillage ─────

    /** La base ne sait plus dire si une conversation est au coffre. */
    private class EtatIllisible(d: ConversationDao) : ConversationDao by d {
        override suspend fun findById(id: Long): ConversationEntity? = error("ETAT_ILLISIBLE")
    }

    /** Écrit une pièce jointe entre la lecture des pièces et la transaction finale — la fenêtre de B5. */
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

    private fun eraserAvec(
        systemCopy: SystemCopyEraser,
        conversationDao: ConversationDao = db.conversationDao(),
        attachmentDao: AttachmentDao = db.attachmentDao(),
    ) = ConversationEraser(
        db,
        conversationDao,
        db.messageDao(),
        systemCopy,
        db.scheduledMessageDao(),
        object : com.filestech.sms.domain.scheduler.ScheduledMessageScheduler {
            override fun scheduleAt(scheduledMessageId: Long, epochMillis: Long) = Unit
            override fun cancel(scheduledMessageId: Long) = Unit
        },
        attachmentDao,
        FichiersDePiecesJointes(attachmentDao, db.scheduledMessageDao(), context),
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

    private suspend fun message(conversationId: Long): Long = db.messageDao().insert(
        MessageEntity(
            conversationId = conversationId,
            telephonyUri = "content://sms/${9_000 + conversationId}",
            address = "+3360000${conversationId.toString().padStart(4, '0')}",
            body = "contenu",
            type = MessageType.SMS,
            direction = MessageDirection.INCOMING,
            date = System.currentTimeMillis(),
            dateSent = null,
            read = true,
            starred = false,
            status = MessageStatus.RECEIVED,
            errorCode = null,
            subId = null,
            scheduledAt = null,
            attachmentsCount = 0,
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

    private companion object {
        const val ALICE = 20L
        const val BOB = 21L
        const val COFFRE = 22L
    }
}
