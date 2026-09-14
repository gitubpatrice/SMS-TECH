package com.filestech.sms.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.mms.ContenuMmsRecu
import com.filestech.sms.data.mms.EcrivainDePiecesSurDisque
import com.filestech.sms.data.mms.IntegrationMmsRecu
import com.filestech.sms.data.mms.IntegrationMmsRecu.Resultat
import com.filestech.sms.data.mms.PartieMms
import com.filestech.sms.data.mms.PdusEnAttente
import com.filestech.sms.data.sms.PhoneIdentity
import com.filestech.sms.data.sms.PhoneNumberWireFormatter
import com.filestech.sms.domain.model.Contact
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
import java.io.File

/**
 * v1.28.9 (F17) — **l'écriture d'un MMS reçu est idempotente**, sur un vrai Room et de vrais fichiers.
 *
 * La reprise rejoue un PDU gardé : elle doit reconnaître le message déjà écrit (sinon doublon), compléter
 * celui dont une pièce manquait (sinon média perdu), ne rien changer quand la complétion échoue encore, et
 * ne rien écrire pour un message supprimé pendant qu'elle travaillait (sinon résurrection). Les pièces sont
 * écrites par l'écrivain réel ; seul son refus est simulé, sur un type choisi.
 *
 * Les tests où rien ne doit être écrit ont leur contrôle positif : [unMmsNeufEstEcritAvecSaCle].
 */
@RunWith(AndroidJUnit4::class)
class IntegrationMmsRecuTest {

    private lateinit var db: AppDatabase
    private lateinit var mirror: ConversationMirror
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Chaque fichier écrit par un test, pour le ménage — et pour vérifier ce qui a été effacé. */
    private val ecrits = mutableListOf<File>()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val cle = PdusEnAttente.cle("TX-1", "http://mmsc.example/1", 1)!!

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
        ecrits.forEach { it.delete() }
    }

    @Test
    fun unMmsNeufEstEcritAvecSaCle(): Unit = runBlocking {
        val resultat = integration().integrer(contenu(), subId = 1, membres = null, cle = cle) { true }

        assertThat(resultat).isInstanceOf(Resultat.Ecrit::class.java)
        val id = (resultat as Resultat.Ecrit).messageId
        assertThat(db.messageDao().findById(id)?.mmsTransactionKey).isEqualTo(cle)
        val pieces = db.attachmentDao().findForMessage(id)
        assertThat(pieces.map { it.mimeType }).containsExactly("image/jpeg", "image/png").inOrder()
        assertThat(pieces.all { File(it.localUri).exists() }).isTrue()
    }

    @Test
    fun unSecondExemplaireEstReconnuSansRienEcrire(): Unit = runBlocking {
        val premier = integration().integrer(contenu(), 1, null, cle) { true } as Resultat.Ecrit
        val ecritsAvant = ecrits.size

        val second = integration().integrer(contenu(), 1, null, cle) { true }

        assertThat(second).isEqualTo(Resultat.DejaComplet(premier.messageId))
        assertThat(ecrits.size).isEqualTo(ecritsAvant)
        assertThat(nombreDeMessages()).isEqualTo(1)
    }

    @Test
    fun unePieceNonEcriteEstCompleteeALaReprise(): Unit = runBlocking {
        val incomplet = integration(ecrivainReel("image/png")).integrer(contenu(), 1, null, cle) { true }
        assertThat(incomplet).isInstanceOf(Resultat.EcritIncomplet::class.java)
        val id = (incomplet as Resultat.EcritIncomplet).messageId
        val avant = db.attachmentDao().findForMessage(id).map { it.localUri }
        assertThat(avant).hasSize(1)
        assertThat(db.messageDao().findById(id)?.attachmentsCount).isEqualTo(1)

        val repris = integration().integrer(contenu(), 1, null, cle) { true }

        assertThat(repris).isEqualTo(Resultat.Complete(id))
        val apres = db.attachmentDao().findForMessage(id)
        assertThat(apres.map { it.mimeType }).containsExactly("image/jpeg", "image/png").inOrder()
        assertThat(apres.all { File(it.localUri).exists() }).isTrue()
        assertThat(db.messageDao().findById(id)?.attachmentsCount).isEqualTo(2)
        // L'ancienne pièce, remplacée et plus citée par rien, est partie : pas d'orphelin.
        assertThat(File(avant.single()).exists()).isFalse()
        assertThat(nombreDeMessages()).isEqualTo(1)
    }

    @Test
    fun uneCompletionQuiEchoueEncoreNeChangeRienEtEffaceCeQuElleAEcrit(): Unit = runBlocking {
        val premier = integration(ecrivainReel("image/png")).integrer(contenu(), 1, null, cle) { true }
        val id = (premier as Resultat.EcritIncomplet).messageId
        val avant = db.attachmentDao().findForMessage(id).map { it.localUri }
        val ecritsAvant = ecrits.size

        val repris = integration(ecrivainReel("image/png")).integrer(contenu(), 1, null, cle) { true }

        assertThat(repris).isEqualTo(Resultat.ToujoursIncomplet(id))
        assertThat(db.attachmentDao().findForMessage(id).map { it.localUri }).isEqualTo(avant)
        assertThat(File(avant.single()).exists()).isTrue()
        // Le JPEG réécrit pour la complétion n'est cité par rien : il ne reste pas.
        val reecrits = ecrits.drop(ecritsAvant)
        assertThat(reecrits).hasSize(1)
        assertThat(reecrits.single().exists()).isFalse()
    }

    @Test
    fun unMessageSupprimePendantLaCompletionNeReprendPasDePieces(): Unit = runBlocking {
        val premier = integration(ecrivainReel("image/png")).integrer(contenu(), 1, null, cle) { true }
        val id = (premier as Resultat.EcritIncomplet).messageId
        val ecritsAvant = ecrits.size
        var supprime = false
        val suppressionPendantLEcriture = ecrivainReel(
            avant = {
                if (!supprime) {
                    supprime = true
                    runBlocking {
                        db.attachmentDao().deleteForMessage(id)
                        db.messageDao().delete(id)
                    }
                }
            },
        )

        val repris = integration(suppressionPendantLEcriture).integrer(contenu(), 1, null, cle) { true }

        assertThat(supprime).isTrue()
        assertThat(repris).isEqualTo(Resultat.Disparu)
        assertThat(db.messageDao().findById(id)).isNull()
        assertThat(db.attachmentDao().findForMessage(id)).isEmpty()
        val reecrits = ecrits.drop(ecritsAvant)
        assertThat(reecrits).hasSize(2)
        assertThat(reecrits.none { it.exists() }).isTrue()
    }

    /**
     * La porte : un PDU effacé avec son message pendant l'écriture des pièces n'inscrit rien — sans elle, la
     * reprise qui l'avait lu juste avant écrivait de nouveau le message supprimé.
     */
    @Test
    fun unPduEffacePendantLEcritureNInscritRienEtNeLaisseAucunFichier(): Unit = runBlocking {
        val resultat = integration().integrer(contenu(), 1, null, cle) { false }

        assertThat(resultat).isEqualTo(Resultat.Disparu)
        assertThat(db.messageDao().findIdByTransactionKey(cle)).isNull()
        assertThat(nombreDeMessages()).isEqualTo(0)
        assertThat(ecrits).hasSize(2)
        assertThat(ecrits.none { it.exists() }).isTrue()
    }

    /** Limite écrite : sans clé, rien ne reconnaît le second exemplaire. */
    @Test
    fun sansCleDeuxExemplairesFontDeuxMessages(): Unit = runBlocking {
        val premier = integration().integrer(contenu(), 1, null, cle = null) { true }
        val second = integration().integrer(contenu(), 1, null, cle = null) { true }

        assertThat(premier).isInstanceOf(Resultat.Ecrit::class.java)
        assertThat(second).isInstanceOf(Resultat.Ecrit::class.java)
        assertThat(nombreDeMessages()).isEqualTo(2)
    }

    // ───── Outillage ─────

    /** L'écrivain réel, qui refuse les types de [refuses] et note chaque fichier écrit. */
    private fun ecrivainReel(vararg refuses: String, avant: () -> Unit = {}) =
        IntegrationMmsRecu.EcrivainDePieces { partie ->
            avant()
            if (partie.mime in refuses) {
                null
            } else {
                EcrivainDePiecesSurDisque(context).ecrire(partie)?.also { ecrits += it.file }
            }
        }

    private fun integration(ecrivain: IntegrationMmsRecu.EcrivainDePieces = ecrivainReel()) = IntegrationMmsRecu(
        mirror,
        db.attachmentDao(),
        db.messageDao(),
        FichiersDePiecesJointes(db.attachmentDao(), db.scheduledMessageDao(), context),
        ecrivain,
    )

    private fun contenu() = ContenuMmsRecu(
        expediteur = ALICE,
        date = 1_726_000_000_000L,
        sujet = null,
        legende = "photos",
        parties = listOf(PartieMms(byteArrayOf(1, 2, 3), "image/jpeg"), PartieMms(byteArrayOf(4, 5), "image/png")),
        destinataires = emptyList(),
        copies = emptyList(),
    )

    private fun nombreDeMessages(): Int = db.query("SELECT COUNT(*) FROM messages", null).use { curseur ->
        curseur.moveToFirst()
        curseur.getInt(0)
    }

    private companion object {
        const val ALICE = "+33612345678"
    }
}
