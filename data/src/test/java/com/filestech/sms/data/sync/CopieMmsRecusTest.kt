package com.filestech.sms.data.sync

import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.AttachmentEntity
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.data.mms.MmsSystemWriteback
import com.filestech.sms.data.mms.PdusEnAttente
import com.filestech.sms.data.sms.SystemCopyEraser
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * 2026-11-19 (issue #98) — ce que la copie système des MMS reçus DÉCIDE. L'écriture elle-même se mesure contre le
 * vrai fournisseur (`CopieMmsRecusSystemeTest`).
 *
 * Ce que ces cas fixent : rien du Coffre ne part dans le stockage partagé, et ce qui y était parti avant l'entrée au
 * Coffre en est retiré ; un message incomplet (PDU gardé, pièce absente) attend ; une copie est notée au journal dès
 * qu'elle existe, et le passage suivant efface celle qu'aucun message d'ici ne porte plus (supprimé pendant sa copie,
 * passage interrompu) — mais seulement une fois le délai écoulé, et jamais une copie reliée.
 */
class CopieMmsRecusTest {

    @TempDir
    lateinit var dossier: File

    private val messages = mockk<MessageDao>(relaxed = true)
    private val pieces = mockk<AttachmentDao>()
    private val conversations = mockk<ConversationDao>()
    private val ecrivain = mockk<MmsSystemWriteback>(relaxed = true)
    private val pdus = mockk<PdusEnAttente> { every { existePourCles(any()) } returns false }
    private val journal = mockk<JournalCopiesMms>(relaxed = true) { coEvery { lire() } returns emptyList() }
    private val effaceur = mockk<SystemCopyEraser>()
    private val copie = CopieMmsRecus(messages, pieces, conversations, ecrivain, pdus, journal, effaceur)
        .apply { horloge = { MAINTENANT } }

    private fun preparer(enCoffre: Boolean = false, fichierPresent: Boolean = true) {
        val fichier = File(dossier, "in-1.jpg")
        if (fichierPresent) fichier.writeBytes(byteArrayOf(1, 2, 3))
        coEvery { messages.mmsRecusSansCopieSysteme(any(), any()) } returns listOf(MESSAGE)
        coEvery { messages.mmsRecusCopiesAuCoffre(any()) } returns emptyList()
        coEvery { pieces.findForMessage(MESSAGE.id) } returns listOf(
            AttachmentEntity(
                messageId = MESSAGE.id,
                mimeType = "image/jpeg",
                fileName = fichier.name,
                sizeBytes = 3L,
                localUri = fichier.absolutePath,
                width = null,
                height = null,
                durationMs = null,
            ),
        )
        coEvery { conversations.findById(CONVERSATION) } returns mockk<ConversationEntity> {
            every { inVault } returns enCoffre
            every { addressesCsv } returns ALICE
        }
    }

    /** L'écrivain crée la ligne [ID_SYSTEME], la signale par `creee`, puis la rend. */
    private fun copieEcrite() {
        coEvery {
            ecrivain.insertInbox(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            arg<suspend (Long) -> Unit>(8).invoke(ID_SYSTEME)
            ID_SYSTEME
        }
    }

    @Test
    fun `un message complet est recopie, note au journal, puis relie`() = runTest {
        preparer()
        copieEcrite()
        coEvery { messages.lierCopieSysteme(MESSAGE.id, "content://mms/$ID_SYSTEME") } returns 1

        assertThat(copie.copier(depuis = 0L, maintenant = MAINTENANT)).isEqualTo(1)

        coVerify(exactly = 1) { journal.noter(IdDate(ID_SYSTEME, MAINTENANT)) }
        coVerify(exactly = 0) { ecrivain.effacerCopieRecue(any()) }
    }

    /** Contrôle négatif : retirer la garde du Coffre ferait écrire ses photos dans le stockage partagé. */
    @Test
    fun `une conversation entree au Coffre n'est pas recopiee`() = runTest {
        preparer(enCoffre = true)

        assertThat(copie.copier(depuis = 0L, maintenant = MAINTENANT)).isEqualTo(0)
        coVerify(exactly = 0) { ecrivain.insertInbox(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `un PDU encore garde fait attendre la copie`() = runTest {
        preparer()
        every { pdus.existePourCles(listOf(CLE)) } returns true

        assertThat(copie.copier(depuis = 0L, maintenant = MAINTENANT)).isEqualTo(0)
        coVerify(exactly = 0) { ecrivain.insertInbox(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `une piece absente du disque fait attendre la copie`() = runTest {
        preparer(fichierPresent = false)

        assertThat(copie.copier(depuis = 0L, maintenant = MAINTENANT)).isEqualTo(0)
        coVerify(exactly = 0) { ecrivain.insertInbox(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    /** Contrôle négatif : sans l'effacement, la copie ressusciterait un message supprimé. */
    @Test
    fun `une copie dont le message est supprime pendant le travail est effacee`() = runTest {
        preparer()
        copieEcrite()
        coEvery { messages.lierCopieSysteme(any(), any()) } returns 0

        assertThat(copie.copier(depuis = 0L, maintenant = MAINTENANT)).isEqualTo(0)
        coVerify(exactly = 1) { ecrivain.effacerCopieRecue(ID_SYSTEME) }
    }

    /**
     * Le message supprimé APRÈS le lien, par un effaceur qui l'avait lu avant (relectures Claude et GPT) : la copie
     * notée n'est plus reliée à rien au passage suivant, elle est effacée. Contrôle négatif : sans la vérification
     * du journal, elle resterait.
     */
    @Test
    fun `le journal efface une copie qu'aucun message ne porte plus`() = runTest {
        coEvery { messages.mmsRecusSansCopieSysteme(any(), any()) } returns emptyList()
        coEvery { messages.mmsRecusCopiesAuCoffre(any()) } returns emptyList()
        coEvery { journal.lire() } returns listOf(IdDate(ID_SYSTEME, MAINTENANT - CopieMmsRecus.DELAI_VERIFICATION_MS))
        coEvery { messages.telephonyUrisParmi(any()) } returns emptyList()
        coEvery { ecrivain.effacerCopieRecue(ID_SYSTEME) } returns 1

        copie.copier(depuis = 0L, maintenant = MAINTENANT)

        coVerify(exactly = 1) { ecrivain.effacerCopieRecue(ID_SYSTEME) }
        coVerify(exactly = 1) { journal.retirer(setOf(ID_SYSTEME)) }
    }

    @Test
    fun `le journal garde une copie reliee sans y toucher, et attend le delai`() = runTest {
        coEvery { messages.mmsRecusSansCopieSysteme(any(), any()) } returns emptyList()
        coEvery { messages.mmsRecusCopiesAuCoffre(any()) } returns emptyList()
        val reliee = IdDate(ID_SYSTEME, MAINTENANT - CopieMmsRecus.DELAI_VERIFICATION_MS)
        val tropRecente = IdDate(ID_SYSTEME + 1, MAINTENANT - CopieMmsRecus.DELAI_VERIFICATION_MS + 1)
        coEvery { journal.lire() } returns listOf(reliee, tropRecente)
        coEvery { messages.telephonyUrisParmi(any()) } returns listOf("content://mms/$ID_SYSTEME")

        copie.copier(depuis = 0L, maintenant = MAINTENANT)

        coVerify(exactly = 0) { ecrivain.effacerCopieRecue(any()) }
        coVerify(exactly = 1) { journal.retirer(setOf(ID_SYSTEME)) }
    }

    @Test
    fun `une copie que le fournisseur n'a pas pu effacer reste au journal`() = runTest {
        coEvery { messages.mmsRecusSansCopieSysteme(any(), any()) } returns emptyList()
        coEvery { messages.mmsRecusCopiesAuCoffre(any()) } returns emptyList()
        coEvery { journal.lire() } returns listOf(IdDate(ID_SYSTEME, 0L))
        coEvery { messages.telephonyUrisParmi(any()) } returns emptyList()
        coEvery { ecrivain.effacerCopieRecue(ID_SYSTEME) } returns null

        copie.copier(depuis = 0L, maintenant = MAINTENANT)

        coVerify(exactly = 1) { journal.retirer(emptySet()) }
    }

    /** Contrôle négatif : sans le balayage, une photo copiée avant l'entrée au Coffre resterait partagée. */
    @Test
    fun `un MMS recopie puis mis au Coffre perd sa copie et son lien`() = runTest {
        val auCoffre = MESSAGE.copy(telephonyUri = "content://mms/$ID_SYSTEME")
        coEvery { messages.mmsRecusSansCopieSysteme(any(), any()) } returns emptyList()
        coEvery { messages.mmsRecusCopiesAuCoffre(any()) } returns listOf(auCoffre)
        every { effaceur.erase(auCoffre) } returns true

        copie.copier(depuis = 0L, maintenant = MAINTENANT)

        coVerify(exactly = 1) { messages.delierCopieSysteme(MESSAGE.id, "content://mms/$ID_SYSTEME") }
    }

    @Test
    fun `une copie au Coffre qui resiste garde son lien pour le passage suivant`() = runTest {
        val auCoffre = MESSAGE.copy(telephonyUri = "content://mms/$ID_SYSTEME")
        coEvery { messages.mmsRecusSansCopieSysteme(any(), any()) } returns emptyList()
        coEvery { messages.mmsRecusCopiesAuCoffre(any()) } returns listOf(auCoffre)
        every { effaceur.erase(auCoffre) } returns false

        copie.copier(depuis = 0L, maintenant = MAINTENANT)

        coVerify(exactly = 0) { messages.delierCopieSysteme(any(), any()) }
    }

    /**
     * Conversation entrée au Coffre PENDANT la copie (relecture GPT, 2e vague) : la copie reliée en sort aussitôt.
     * Contrôle négatif : sans la relecture après le lien, elle attendrait le passage suivant.
     */
    @Test
    fun `une conversation entree au Coffre pendant la copie perd sa copie aussitot`() = runTest {
        preparer()
        copieEcrite()
        coEvery { messages.lierCopieSysteme(any(), any()) } returns 1
        val horsCoffre = mockk<ConversationEntity> {
            every { inVault } returns false
            every { addressesCsv } returns ALICE
        }
        val auCoffre = mockk<ConversationEntity> { every { inVault } returns true }
        coEvery { conversations.findById(CONVERSATION) } returnsMany listOf(horsCoffre, auCoffre)
        every { effaceur.erase(any()) } returns true

        copie.copier(depuis = 0L, maintenant = MAINTENANT)

        coVerify(exactly = 1) { messages.delierCopieSysteme(MESSAGE.id, "content://mms/$ID_SYSTEME") }
    }

    @Test
    fun `les copies restees en plan plus d'une heure sont purgees a chaque passage`() = runTest {
        coEvery { messages.mmsRecusSansCopieSysteme(any(), any()) } returns emptyList()
        coEvery { messages.mmsRecusCopiesAuCoffre(any()) } returns emptyList()

        copie.copier(depuis = 0L, maintenant = MAINTENANT)

        coVerify(exactly = 1) { ecrivain.purgerCopiesEnPlan(MAINTENANT - CopieMmsRecus.ABANDON_EN_PLAN_MS) }
    }

    /** Un type avec paramètres serait refusé par l'écrivain, et le message retenté sans fin (relecture Claude). */
    @Test
    fun `le type d'une piece est copie sans ses parametres`() = runTest {
        preparer()
        val fichier = File(dossier, "in-1.jpg")
        coEvery { pieces.findForMessage(MESSAGE.id) } returns listOf(
            AttachmentEntity(
                messageId = MESSAGE.id,
                mimeType = "image/jpeg; name=photo.jpg",
                fileName = fichier.name,
                sizeBytes = 3L,
                localUri = fichier.absolutePath,
                width = null,
                height = null,
                durationMs = null,
            ),
        )
        val vus = mutableListOf<List<Pair<File, String>>>()
        coEvery {
            ecrivain.insertInbox(any(), any(), any(), any(), any(), any(), any(), capture(vus), any())
        } returns null

        copie.copier(depuis = 0L, maintenant = MAINTENANT)

        assertThat(vus.single().map { it.second }).containsExactly("image/jpeg")
    }

    @Test
    fun `le fil de la copie est celui du groupe, sinon celui de l'expediteur tel que recu`() {
        assertThat(copie.filDeLaCopie("+33612340098", "+33698760098;+33611110098"))
            .containsExactly("+33698760098", "+33611110098")
        // Conversation 1-à-1 rangée sous la forme nationale : le fil suit l'adresse REÇUE, comme pour un SMS.
        assertThat(copie.filDeLaCopie("+33612340098", "0612340098")).containsExactly("+33612340098")
    }

    @Test
    fun `la fenetre de copie ne remonte ni avant le debut ni au-dela d'une semaine`() = runTest {
        coEvery { messages.mmsRecusSansCopieSysteme(any(), any()) } returns emptyList()
        coEvery { messages.mmsRecusCopiesAuCoffre(any()) } returns emptyList()

        copie.copier(depuis = MAINTENANT - 1_000L, maintenant = MAINTENANT)
        coVerify { messages.mmsRecusSansCopieSysteme(MAINTENANT - 1_000L, any()) }

        copie.copier(depuis = 1L, maintenant = MAINTENANT)
        coVerify { messages.mmsRecusSansCopieSysteme(MAINTENANT - CopieMmsRecus.FENETRE_MS, any()) }
    }

    private companion object {
        const val ALICE = "+33612340098"
        const val CLE = "cle-98"
        const val CONVERSATION = 7L
        const val ID_SYSTEME = 4242L
        const val MAINTENANT = 1_800_000_000_000L

        val MESSAGE = MessageEntity(
            id = 11L,
            conversationId = CONVERSATION,
            telephonyUri = null,
            address = ALICE,
            body = "Bonjour",
            type = MessageType.MMS,
            direction = MessageDirection.INCOMING,
            date = MAINTENANT - 60_000L,
            dateSent = null,
            read = false,
            starred = false,
            status = MessageStatus.RECEIVED,
            errorCode = null,
            subId = null,
            scheduledAt = null,
            attachmentsCount = 1,
            mmsTransactionKey = CLE,
        )
    }
}
