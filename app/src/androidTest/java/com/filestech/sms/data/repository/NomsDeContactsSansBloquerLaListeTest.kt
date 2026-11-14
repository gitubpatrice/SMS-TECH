package com.filestech.sms.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.sms.PhoneIdentity
import com.filestech.sms.data.sms.PhoneNumberWireFormatter
import com.filestech.sms.domain.model.Contact
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.ContactRepository
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 2026-11-11 (issue #90) — **la liste des conversations s'affiche pendant que les noms de contact sont cherchés.**
 *
 * `ConversationsViewModel` lance [ConversationMirror.refreshContactNames] à chaque création de l'écran. Les
 * recherches de contact (un appel au fournisseur de contacts par conversation sans nom) se faisaient DANS une
 * transaction d'écriture Room. Or un nouvel observateur Room pose ses déclencheurs par la connexion
 * d'ÉCRITURE (`TriggerBasedInvalidationTracker.syncTriggers` → `useConnection(isReadOnly = false)`, lu au
 * désassemblage de room-runtime 2.8.5) : la liste attendait la fin de toutes les recherches avant sa première
 * émission, et l'écran restait blanc. Les numéros inconnus ne trouvant jamais de nom, le coût revenait à chaque
 * ouverture.
 *
 * La base est sur FICHIER, en WAL, comme en production : en mémoire, une seule connexion existe et le test
 * prouverait moins. Limite : SQLite du système ici, SQLCipher en production — même pool de connexions, copié
 * d'Android par SQLCipher.
 */
@RunWith(AndroidJUnit4::class)
class NomsDeContactsSansBloquerLaListeTest {

    private lateinit var db: AppDatabase
    private lateinit var mirror: ConversationMirror
    private val contacts = ContactsRetenus()
    /** La collecte permanente de `SettingsRepository`. */
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Le travail du test, seul attendu au démontage. */
    private val travail = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Une recherche de contact qui ne rend son résultat qu'à l'ouverture de [porte], une fois [retenir] armé ;
     * sinon, le nom que [noms] donne au numéro.
     */
    private class ContactsRetenus : ContactRepository {
        @Volatile var retenir: Boolean = false
        @Volatile var noms: Map<String, String> = emptyMap()
        val entree = CompletableDeferred<Unit>()
        val porte = CompletableDeferred<Unit>()

        override suspend fun lookupByPhone(rawPhone: String): Contact? {
            if (!retenir) {
                return noms[rawPhone]?.let { Contact(id = 2L, displayName = it, phones = listOf(PhoneAddress.of(rawPhone))) }
            }
            entree.complete(Unit)
            porte.await()
            return Contact(id = 1L, displayName = NOM_CONTACT, phones = listOf(PhoneAddress.of(rawPhone)))
        }

        override suspend fun listAll(): List<Contact> = emptyList()
    }

    @Before
    fun setUp() {
        context.deleteDatabase(NOM_BASE)
        db = Room.databaseBuilder(context, AppDatabase::class.java, NOM_BASE)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        mirror = ConversationMirror(
            db,
            db.conversationDao(),
            db.messageDao(),
            db.scheduledMessageDao(),
            db.attachmentDao(),
            contacts,
            PhoneIdentity(PhoneNumberWireFormatter(context, SettingsRepository(context, portee))),
            AnnulateurDeNotificationsEspion(),
            Dispatchers.IO,
        )
    }

    @After
    fun tearDown() {
        // La porte s'ouvre toujours : sans elle, un test en échec laisserait une écriture suspendue sur la base
        // qu'on ferme.
        contacts.porte.complete(Unit)
        runBlocking { withTimeoutOrNull(DELAI_MS) { travail.coroutineContext.job.children.toList().joinAll() } }
        travail.cancel()
        portee.cancel()
        db.close()
        context.deleteDatabase(NOM_BASE)
    }

    /** Une conversation sans nom : son correspondant n'était pas un contact à l'arrivée du message. */
    private suspend fun conversationSansNom(): Long {
        mirror.upsertIncomingSms(ALICE, "salut", date = 1_000L, dateSent = null, telephonyUri = null)
        val conversation = db.conversationDao().snapshotAllNonVault().single()
        assertThat(conversation.displayName).isNull()
        return conversation.id
    }

    @Test
    fun laListeSAffichePendantQueLesNomsSontCherches() = runBlocking {
        val id = conversationSansNom()
        contacts.retenir = true
        val rafraichissement = travail.async { mirror.refreshContactNames() }
        withTimeout(DELAI_MS) { contacts.entree.await() }

        // Une recherche de contact est en cours, retenue : c'est l'instant où l'écran ouvre sa liste.
        val lecture = travail.async { db.conversationDao().observeAllNonVault().first() }
        val liste = withTimeoutOrNull(DELAI_MS) { lecture.await() }
        contacts.porte.complete(Unit)

        assertWithMessage("liste non emise pendant la recherche des noms").that(liste).isNotNull()
        assertThat(liste!!.map { it.id }).containsExactly(id)
        // Le nom trouvé est bien écrit, une fois la recherche finie.
        withTimeout(DELAI_MS) { rafraichissement.await() }
        assertThat(db.conversationDao().findById(id)?.displayName).isEqualTo(NOM_CONTACT)
    }

    @Test
    fun unNomDonnePendantLaRechercheNEstPasEcrase() = runBlocking {
        val id = conversationSansNom()
        contacts.retenir = true
        val rafraichissement = travail.async { mirror.refreshContactNames() }
        withTimeout(DELAI_MS) { contacts.entree.await() }

        // Pendant la recherche, la conversation reçoit un nom (renommage d'un groupe, par exemple). Il doit être
        // ÉCRIT pendant la recherche, sans quoi le test ne prouverait pas ce qu'il annonce (relecture GPT) ; l'attente
        // est bornée pour qu'une écriture bloquée par la recherche fasse échouer le test au lieu de le figer.
        val renommage = travail.async { db.conversationDao().setDisplayName(id, NOM_CHOISI) }
        val renomme = withTimeoutOrNull(DELAI_MS) { renommage.await() }
        assertWithMessage("renommage bloque pendant la recherche des noms").that(renomme).isNotNull()
        contacts.porte.complete(Unit)
        withTimeout(DELAI_MS) {
            rafraichissement.await()
            renommage.await()
        }

        assertThat(db.conversationDao().findById(id)?.displayName).isEqualTo(NOM_CHOISI)
    }

    /**
     * Un groupe dont les membres ont changé pendant la recherche ne reçoit pas le nom calculé pour les anciens
     * (relecture GPT du 2026-11-11).
     */
    @Test
    fun unGroupeDontLesMembresOntChangeNeRecoitPasLAncienNom() = runBlocking {
        val id = conversationSansNom()
        val conversation = db.conversationDao().findById(id)!!
        contacts.retenir = true
        val rafraichissement = travail.async { mirror.refreshContactNames() }
        withTimeout(DELAI_MS) { contacts.entree.await() }

        // Bornée comme le renommage : une écriture bloquée par la recherche doit faire échouer, pas figer.
        val changement = travail.async { db.conversationDao().update(conversation.copy(addressesCsv = "$ALICE;$BOB")) }
        val change = withTimeoutOrNull(DELAI_MS) { changement.await() }
        assertWithMessage("changement des membres bloque pendant la recherche des noms").that(change).isNotNull()
        contacts.porte.complete(Unit)
        withTimeout(DELAI_MS) { rafraichissement.await() }

        assertThat(db.conversationDao().findById(id)?.displayName).isNull()
    }

    /**
     * Un groupe sans nom reçoit le titre de ses membres, la règle de sa création (`nomDeGroupe`) : il recevait
     * le nom de son seul premier membre (relecture GPT du 2026-11-11). Un membre inconnu garde son numéro.
     */
    @Test
    fun unGroupeSansNomRecoitLeTitreDeSesMembres() = runBlocking {
        val id = db.conversationDao().insert(
            ConversationEntity(
                threadId = null,
                addressesCsv = "$ALICE;$BOB",
                displayName = null,
                lastMessageAt = 1_000L,
                lastMessagePreview = null,
            ),
        )
        contacts.noms = mapOf(ALICE to NOM_CONTACT)

        mirror.refreshContactNames()

        assertThat(db.conversationDao().findById(id)?.displayName).isEqualTo("$NOM_CONTACT, $BOB")
    }

    private companion object {
        const val NOM_BASE = "noms-sans-bloquer-la-liste.db"
        const val ALICE = "+33600000001"
        const val BOB = "+33600000002"
        const val NOM_CONTACT = "Alice"
        const val NOM_CHOISI = "Famille"
        const val DELAI_MS = 5_000L
    }
}
