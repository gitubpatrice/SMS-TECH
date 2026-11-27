package com.filestech.sms.data.repository

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.entity.AttachmentEntity
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.data.mms.MmsSystemWriteback
import com.filestech.sms.data.mms.PdusEnAttente
import com.filestech.sms.data.sms.PhoneIdentity
import com.filestech.sms.data.sms.PhoneNumberWireFormatter
import com.filestech.sms.data.sms.TelephonyReader
import com.filestech.sms.data.sms.TelephonySystemCopyEraser
import com.filestech.sms.data.sync.CopieMmsRecus
import com.filestech.sms.data.sync.JournalCopiesMms
import com.filestech.sms.domain.model.Contact
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.filestech.sms.domain.repository.ContactRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 2026-11-19 (issue #98, plusieurs profils) — **un MMS reçu est recopié dans le VRAI `content://mms`, et l'import le
 * lit comme le lirait un autre profil du téléphone** ; ce qui ne doit plus y être en part ; et l'import n'y reprend
 * plus nos propres envois.
 *
 * Android garde un seul stockage de messages pour tous les profils mais ne remet les messages entrants qu'au profil
 * principal : les autres ne voient que ce que celui-ci y écrit. Un MMS reçu n'y était jamais écrit (issue #98,
 * GrapheneOS). Et un MMS envoyé d'ici, relié à sa copie par `mms_system_id` seul, revenait en second exemplaire à
 * chaque « Resynchroniser ».
 *
 * Contre le vrai fournisseur, parce qu'un faux affirmerait ce qu'on veut lui faire dire. Écrire dans `content://mms`
 * exige le rôle SMS : sans lui, chaque cas est SAUTÉ (`assume`), jamais vert à tort. Toutes les lignes système
 * écrites ici, et les entrées du journal des copies, sont effacées après chaque cas.
 */
@RunWith(AndroidJUnit4::class)
class CopieMmsRecusSystemeTest {

    private lateinit var db: AppDatabase
    private lateinit var autreProfil: AppDatabase
    private lateinit var copie: CopieMmsRecus
    private lateinit var writeback: MmsSystemWriteback
    private lateinit var reader: TelephonyReader
    private lateinit var journal: JournalCopiesMms
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ecrites = mutableListOf<Long>()
    private val fichiers = mutableListOf<File>()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private object SansContacts : ContactRepository {
        override suspend fun lookupByPhone(rawPhone: String): Contact? = null
        override suspend fun listAll(): List<Contact> = emptyList()
    }

    private fun base() = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    private fun miroir(base: AppDatabase) = ConversationMirror(
        base,
        base.conversationDao(),
        base.messageDao(),
        base.scheduledMessageDao(),
        base.attachmentDao(),
        SansContacts,
        PhoneIdentity(PhoneNumberWireFormatter(context, SettingsRepository(context, portee))),
        AnnulateurDeNotificationsEspion(),
        Dispatchers.IO,
    )

    @Before
    fun setUp() {
        db = base()
        autreProfil = base()
        writeback = MmsSystemWriteback(context, Dispatchers.IO)
        reader = TelephonyReader(context)
        journal = JournalCopiesMms(SettingsRepository(context, portee))
        copie = CopieMmsRecus(
            db.messageDao(),
            db.attachmentDao(),
            db.conversationDao(),
            writeback,
            PdusEnAttente(context),
            journal,
            TelephonySystemCopyEraser(context),
        )
    }

    @After
    fun tearDown() = runBlocking {
        ecrites.forEach { id ->
            runCatching {
                context.contentResolver.delete(ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id), null, null)
            }
        }
        runCatching { journal.retirer(journal.lire().map { it.id }.toSet()) }
        fichiers.forEach { it.delete() }
        db.close()
        autreProfil.close()
    }

    @Test
    fun unMmsRecu_estRecopie_etUnAutreProfilLeLitCommeUnMessageRecu() = runBlocking {
        val messageId = mmsRecu(ALICE, "Bonjour 98", enCoffre = false)

        assertThat(copie.copier(depuis = 0L)).isEqualTo(1)

        val idSysteme = idSystemeDe(messageId)
        val lignes = lire(idSysteme)
        assertThat(lignes).hasSize(1)
        val ligne = lignes.single()
        assertThat(ligne.direction).isEqualTo(MessageDirection.INCOMING)
        assertThat(ligne.address).isEqualTo(ALICE)
        assertThat(ligne.textBody).isEqualTo("Bonjour 98")
        assertThat(ligne.attachments.map { it.contentType }).containsExactly("image/png")
        val octets = context.contentResolver
            .openInputStream(Uri.parse("content://mms/part/${ligne.attachments.single().partId}"))
            ?.use { it.readBytes() }
        assertThat(octets).isEqualTo(OCTETS)
        assertThat(journal.lire().map { it.id }).contains(idSysteme)

        // Ce profil-ci ne le réimporte pas : la copie est reliée à son message.
        miroir(db).bulkImportMmsFromTelephony(lignes)
        assertThat(nombreDeMessages(db)).isEqualTo(1)

        // Un autre profil, qui ne l'a jamais reçu, le voit arriver comme un message reçu d'Alice.
        miroir(autreProfil).bulkImportMmsFromTelephony(lignes)
        val importe = autreProfil.messageDao().findByTelephonyUri("content://mms/$idSysteme")
        assertThat(importe).isNotNull()
        assertThat(importe!!.direction).isEqualTo(MessageDirection.INCOMING)
        assertThat(importe.address).isEqualTo(ALICE)
    }

    /**
     * 2026-11-27 (issue #98) — la copie garde l'état lu du message. Le fournisseur marque lue toute ligne insérée hors
     * de la réception, et la copie naît dans la boîte d'envoi : un MMS pas encore lu arrivait LU dans les autres
     * profils, sans pastille ni rien à y notifier (mesuré sur émulateurs à deux utilisateurs). Le message déjà lu
     * vérifie qu'on ne force pas l'inverse.
     */
    @Test
    fun laCopieGardeLEtatLuDuMessage() = runBlocking {
        val nonLu = mmsRecu(ALICE, "Pas encore lu", enCoffre = false)
        val dejaLu = mmsRecu(BOB, "Deja lu", enCoffre = false, lu = true)

        assertThat(copie.copier(depuis = 0L)).isEqualTo(2)

        val copieNonLue = idSystemeDe(nonLu)
        val copieLue = idSystemeDe(dejaLu)
        assertThat(luEtVu(copieNonLue)).isEqualTo(0 to 0)
        assertThat(luEtVu(copieLue)).isEqualTo(1 to 1)
        // Ce qu'un autre profil importe.
        assertThat(lire(copieNonLue).single().read).isFalse()
        assertThat(lire(copieLue).single().read).isTrue()
    }

    /**
     * Supprimer le message ici efface sa copie : l'effaceur vérifie l'identité de la ligne (date à la seconde, boîte,
     * expéditeur) avant d'y toucher, et la copie doit donc la satisfaire. Effacement de panique compris : il passe
     * par le même effaceur.
     */
    @Test
    fun supprimerLeMessage_effaceSaCopie() = runBlocking {
        val messageId = mmsRecu(ALICE, "A effacer", enCoffre = false)
        assertThat(copie.copier(depuis = 0L)).isEqualTo(1)
        val idSysteme = idSystemeDe(messageId)

        val efface = TelephonySystemCopyEraser(context).erase(db.messageDao().findById(messageId)!!)

        assertThat(efface).isTrue()
        assertThat(existe(idSysteme)).isFalse()
    }

    /**
     * Le message supprimé APRÈS le lien par un effaceur qui l'avait lu AVANT (il ne savait rien de la copie) : le
     * passage suivant de la copie, une fois le délai écoulé, efface la copie orpheline. Contrôle négatif : sans la
     * vérification du journal, elle reste, et l'import la ferait revenir.
     */
    @Test
    fun unMessageSupprimeApresLeLien_saCopieEstEffaceeAuPassageSuivant() = runBlocking {
        val messageId = mmsRecu(ALICE, "Supprime pendant la copie", enCoffre = false)
        assertThat(copie.copier(depuis = 0L)).isEqualTo(1)
        val idSysteme = idSystemeDe(messageId)

        db.messageDao().delete(messageId)
        copie.copier(depuis = 0L, maintenant = System.currentTimeMillis() + APRES_LE_DELAI)

        assertThat(existe(idSysteme)).isFalse()
        assertThat(journal.lire().map { it.id }).doesNotContain(idSysteme)
    }

    /**
     * Passage interrompu entre la copie et son lien : la copie orpheline est effacée, une nouvelle est écrite et
     * reliée — jamais deux copies du même message dans le stockage partagé.
     */
    @Test
    fun uneCopieInterrompueAvantSonLien_estRemplacee_pasDupliquee() = runBlocking {
        val messageId = mmsRecu(ALICE, "Une seule fois", enCoffre = false)
        assertThat(copie.copier(depuis = 0L)).isEqualTo(1)
        val premiere = idSystemeDe(messageId)

        db.openHelper.writableDatabase.execSQL("UPDATE messages SET telephony_uri = NULL WHERE id = $messageId")
        assertThat(copie.copier(depuis = 0L, maintenant = System.currentTimeMillis() + APRES_LE_DELAI)).isEqualTo(1)

        val seconde = idSystemeDe(messageId)
        assertThat(seconde).isNotEqualTo(premiere)
        assertThat(existe(premiere)).isFalse()
        val date = db.messageDao().findById(messageId)!!.date / 1000L
        assertThat(copiesRecuesA(date)).isEqualTo(1)
    }

    @Test
    fun unMmsRecuAuCoffre_nEstPasRecopie() = runBlocking {
        val messageId = mmsRecu(ALICE, "Secret", enCoffre = true)

        assertThat(copie.copier(depuis = 0L)).isEqualTo(0)

        assertThat(db.messageDao().findById(messageId)!!.telephonyUri).isNull()
        assertThat(copiesRecuesA(db.messageDao().findById(messageId)!!.date / 1000L)).isEqualTo(0)
    }

    /** Une conversation mise au Coffre APRÈS la copie de ses MMS : la copie part du stockage partagé, le lien aussi. */
    @Test
    fun unMmsRecopiePuisMisAuCoffre_perdSaCopie() = runBlocking {
        val messageId = mmsRecu(ALICE, "Bientot au coffre", enCoffre = false)
        assertThat(copie.copier(depuis = 0L)).isEqualTo(1)
        val idSysteme = idSystemeDe(messageId)
        val conversation = db.messageDao().findById(messageId)!!.conversationId

        db.openHelper.writableDatabase.execSQL("UPDATE conversations SET in_vault = 1 WHERE id = $conversation")
        copie.copier(depuis = 0L)

        assertThat(existe(idSysteme)).isFalse()
        assertThat(db.messageDao().findById(messageId)!!.telephonyUri).isNull()
    }

    /**
     * Une copie en cours d'écriture vit dans la boîte d'envoi : l'import d'un autre profil ne la prend pas, alors
     * même qu'elle porte déjà son expéditeur et son texte. Contrôle négatif : une tranche sans le filtre des boîtes
     * la lirait (relecture Claude : un profil qui ne partage pas notre verrou la gardait incomplète).
     */
    @Test
    fun uneCopieEnCoursDEcriture_nEstPasImportee() = runBlocking {
        val id = ligneEnCoursDEcriture(ALICE, "Pas encore complete")

        assertThat(lire(id)).isEmpty()
    }

    /**
     * Une copie abandonnée en plan depuis plus d'une heure (processus tué avant sa note au journal) est purgée ; une
     * copie en cours d'écriture, et un envoi resté en boîte d'envoi, même vieux, ne le sont pas.
     */
    @Test
    fun lesCopiesAbandonneesEnPlan_sontPurgees_etRienDAutre() = runBlocking {
        val ilYaDeuxHeures = System.currentTimeMillis() / 1000L - 2L * 3600L
        val abandonnee = ligneEnCoursDEcriture(ALICE, "Abandonnee", dateSec = ilYaDeuxHeures)
        val enCours = ligneEnCoursDEcriture(ALICE, "En cours")
        val envoiAncien = ligneEnCoursDEcriture(BOB, "Envoi ancien", dateSec = ilYaDeuxHeures, type = MMS_TYPE_SEND_REQ)

        writeback.purgerCopiesEnPlan(System.currentTimeMillis() - UNE_HEURE_MS)

        assertThat(existe(abandonnee)).isFalse()
        assertThat(existe(enCours)).isTrue()
        assertThat(existe(envoiAncien)).isTrue()
    }

    /** Une ligne que le journal n'a pas pu noter n'est retrouvée par rien : elle est effacée sur-le-champ. */
    @Test
    fun uneCopieQueLeJournalNaPasPuNoter_nestPasLaissee() = runBlocking {
        var creee = 0L
        val echec = runCatching {
            writeback.insertInbox(
                expediteur = ALICE,
                destinataires = emptyList(),
                membresDuFil = listOf(ALICE),
                dateMs = System.currentTimeMillis(),
                lu = false,
                subId = null,
                textBody = "Journal en panne",
                fichiers = emptyList(),
                creee = { id ->
                    creee = id
                    error("journal indisponible")
                },
            )
        }
        assumeTrue("ecriture dans content://mms refusee (role SMS ?)", creee > 0L)
        ecrites += creee

        assertThat(echec.isFailure).isTrue()
        assertThat(existe(creee)).isFalse()
    }

    /** Le chemin d'effacement des copies ne peut jamais effacer un MMS ENVOYÉ, même sur son identifiant exact. */
    @Test
    fun effacerUneCopieRecue_neTouchePasAUnMmsEnvoye() = runBlocking {
        val idSysteme = writeback.insertOutbox(listOf(BOB), emptyList(), "Envoi 98")
        assumeTrue("ecriture dans content://mms refusee (role SMS ?)", idSysteme != null && idSysteme > 0L)
        ecrites += idSysteme!!

        assertThat(writeback.effacerCopieRecue(idSysteme)).isEqualTo(0)
        assertThat(existe(idSysteme)).isTrue()
    }

    /**
     * Le doublon de « Resynchroniser » : un MMS envoyé d'ici, relié par `mms_system_id` seul, était réimporté.
     * Contrôle négatif : sans le filtre de `bulkImportMmsFromTelephony`, la base compte deux messages.
     */
    @Test
    fun unMmsEnvoyeDIci_nEstPasReimporte() = runBlocking {
        val idSysteme = writeback.insertOutbox(listOf(BOB), emptyList(), "Envoi 98")
        assumeTrue("ecriture dans content://mms refusee (role SMS ?)", idSysteme != null && idSysteme > 0L)
        ecrites += idSysteme!!
        assertThat(writeback.markSent(idSysteme)).isTrue()
        val conversation = db.conversationDao().insert(conversation(BOB, enCoffre = false))
        db.messageDao().insert(
            message(conversation, BOB, "Envoi 98", MessageDirection.OUTGOING).copy(
                status = MessageStatus.SENT,
                mmsSystemId = idSysteme,
            ),
        )

        miroir(db).bulkImportMmsFromTelephony(lire(idSysteme))

        assertThat(nombreDeMessages(db)).isEqualTo(1)
    }

    // ───────────────────────────── outils ─────────────────────────────

    private suspend fun mmsRecu(expediteur: String, texte: String, enCoffre: Boolean, lu: Boolean = false): Long {
        val conversation = db.conversationDao().insert(conversation(expediteur, enCoffre))
        val messageId = db.messageDao().insert(
            message(conversation, expediteur, texte, MessageDirection.INCOMING)
                .copy(mmsTransactionKey = "cle-test-${System.nanoTime()}", attachmentsCount = 1, read = lu),
        )
        val fichier = File(context.filesDir, "mms_attachments/test-98-${System.nanoTime()}.png")
        fichier.parentFile?.mkdirs()
        fichier.writeBytes(OCTETS)
        fichiers += fichier
        db.attachmentDao().insert(
            AttachmentEntity(
                messageId = messageId,
                mimeType = "image/png",
                fileName = fichier.name,
                sizeBytes = fichier.length(),
                localUri = fichier.absolutePath,
                width = null,
                height = null,
                durationMs = null,
            ),
        )
        return messageId
    }

    /** L'id système auquel le message est relié ; SAUTE le cas si l'écriture a été refusée (pas de rôle SMS). */
    private suspend fun idSystemeDe(messageId: Long): Long {
        val uri = db.messageDao().findById(messageId)!!.telephonyUri
        assumeTrue("copie non ecrite (role SMS ?)", uri != null)
        val id = uri!!.removePrefix("content://mms/").toLong()
        if (id !in ecrites) ecrites += id
        return id
    }

    /**
     * Une ligne comme la copie en cours d'écriture : boîte d'envoi, `m-retrieve-conf` (ou [type]), expéditeur et
     * texte, datée de [dateSec].
     */
    private fun ligneEnCoursDEcriture(
        expediteur: String,
        texte: String,
        dateSec: Long = System.currentTimeMillis() / 1000L,
        type: Int = MMS_TYPE_RETRIEVE_CONF,
    ): Long {
        val ligne = ContentValues().apply {
            put(Telephony.Mms.DATE, dateSec)
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_OUTBOX)
            put(Telephony.Mms.MESSAGE_TYPE, type)
            put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related")
        }
        val uri = runCatching { context.contentResolver.insert(Telephony.Mms.Outbox.CONTENT_URI, ligne) }.getOrNull()
        val id = uri?.lastPathSegment?.toLongOrNull() ?: 0L
        assumeTrue("ecriture dans content://mms refusee (role SMS ?)", id > 0L)
        ecrites += id
        context.contentResolver.insert(
            Uri.parse("content://mms/$id/addr"),
            ContentValues().apply {
                put("address", expediteur)
                put("type", PDU_FROM)
                put("charset", CHARSET_UTF8)
            },
        )
        context.contentResolver.insert(
            Uri.parse("content://mms/$id/part"),
            ContentValues().apply {
                put("mid", id)
                put("ct", "text/plain")
                put("chset", CHARSET_UTF8)
                put("text", texte)
            },
        )
        return id
    }

    private suspend fun lire(idSysteme: Long): List<TelephonyReader.MmsImportRow> {
        val lignes = mutableListOf<TelephonyReader.MmsImportRow>()
        val complete = reader.readMmsBatched(
            pageSize = 10,
            tranche = TelephonyReader.TrancheMms.entre(apres = idSysteme - 1, jusqua = idSysteme),
        ) { lignes += it }
        assertThat(complete).isTrue()
        return lignes
    }

    private fun existe(idSysteme: Long): Boolean = context.contentResolver.query(
        ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, idSysteme),
        arrayOf(Telephony.Mms._ID),
        null,
        null,
        null,
    )?.use { it.count > 0 } ?: false

    /** Les colonnes `read` et `seen` de la ligne système [idSysteme]. */
    private fun luEtVu(idSysteme: Long): Pair<Int, Int>? = context.contentResolver.query(
        ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, idSysteme),
        arrayOf(Telephony.Mms.READ, Telephony.Mms.SEEN),
        null,
        null,
        null,
    )?.use { c -> if (c.moveToFirst()) c.getInt(0) to c.getInt(1) else null }

    private fun nombreDeMessages(base: AppDatabase): Int =
        base.openHelper.readableDatabase.query("SELECT COUNT(*) FROM messages").use { c ->
            c.moveToFirst()
            c.getInt(0)
        }

    private fun copiesRecuesA(dateSec: Long): Int = context.contentResolver.query(
        Telephony.Mms.CONTENT_URI,
        arrayOf(Telephony.Mms._ID),
        "${Telephony.Mms.MESSAGE_BOX}=? AND ${Telephony.Mms.DATE}=?",
        arrayOf(Telephony.Mms.MESSAGE_BOX_INBOX.toString(), dateSec.toString()),
        null,
    )?.use { c ->
        var n = 0
        while (c.moveToNext()) {
            val id = c.getLong(0)
            if (id !in ecrites) ecrites += id
            n++
        }
        n
    } ?: 0

    private fun conversation(adresse: String, enCoffre: Boolean) = ConversationEntity(
        threadId = null,
        addressesCsv = adresse,
        displayName = null,
        lastMessagePreview = "",
        lastMessageAt = 0L,
        unreadCount = 0,
        inVault = enCoffre,
    )

    private fun message(conversationId: Long, adresse: String, texte: String, sens: MessageDirection) = MessageEntity(
        conversationId = conversationId,
        telephonyUri = null,
        address = adresse,
        body = texte,
        type = MessageType.MMS,
        direction = sens,
        // À la seconde : la copie système compte en secondes.
        date = (System.currentTimeMillis() / 1000L) * 1000L - DECALAGE_MS,
        dateSent = null,
        read = false,
        starred = false,
        status = MessageStatus.RECEIVED,
        errorCode = null,
        subId = null,
        scheduledAt = null,
        attachmentsCount = 0,
    )

    private companion object {
        const val ALICE = "+33612340098"
        const val BOB = "+33698760098"
        const val MMS_TYPE_RETRIEVE_CONF = 132
        const val MMS_TYPE_SEND_REQ = 128

        /** `CopieMmsRecus.ABANDON_EN_PLAN_MS`, interne au module `data`. */
        const val UNE_HEURE_MS = 60L * 60L * 1_000L
        const val PDU_FROM = 137
        const val CHARSET_UTF8 = 106

        /** Au-delà du délai de vérification du journal (`CopieMmsRecus.DELAI_VERIFICATION_MS`, une minute). */
        const val APRES_LE_DELAI = 2L * 60L * 1_000L

        /** Une seconde d'écart par cas, pour qu'aucun ne compte la copie d'un autre lancé dans la même seconde. */
        val DECALAGE_MS: Long = (System.nanoTime() % 50L) * 1_000L
        val OCTETS = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 98)
    }
}
