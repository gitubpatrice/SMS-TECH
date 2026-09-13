package com.filestech.sms.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
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

/**
 * v1.28.1 (audit de coherence du 2026-09-08) — **la purge de retention efface aussi la copie
 * systeme**.
 *
 * # Le defaut
 *
 * `delete`, `deleteMessage` et `deleteAllInVault` propageaient au fournisseur du systeme depuis
 * longtemps. La purge de retention, non : un `DELETE` SQL, et rien d'autre. Les messages que
 * l'utilisateur croyait effaces restaient dans `content://sms` — lisibles par toute application
 * ayant `READ_SMS` — et le bouton « Resynchroniser », qui remet le curseur d'import a zero, les
 * ramenait tous. Pour un reglage vendu comme une mesure de confidentialite, c'est le defaut le
 * plus couteux qu'on puisse avoir : il ne se voit pas.
 *
 * Rien ne le documentait : ni le code, ni le KDoc de `ConversationRepository.purgeHistoryNow`.
 *
 * # Ce que ces tests figent
 *
 * L'effaceur systeme est un espion : il enregistre ce qu'on lui presente. Un test qui verifierait
 * seulement « la base est vide » resterait vert avec l'ancien code, puisque le `DELETE` SQL, lui,
 * fonctionnait. **Ce qui doit etre prouve, c'est la PRESENTATION au fournisseur** — sans quoi le
 * test ne mesure rien de ce qui manquait.
 */
@RunWith(AndroidJUnit4::class)
class PurgeHistoryPropagationTest {

    private lateinit var db: AppDatabase

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Espion : retient chaque message presente au fournisseur, et laisse tout partir.
     *
     * v1.28.3 (F10) — il retient desormais le LIEN reel, `telephony_uri` **ou** `mms_system_id`.
     * N'observer que le premier revenait a ne pas voir les MMS sortants, qui n'en ont jamais :
     * l'espion aurait enregistre `null` pour eux et le defaut serait passe sous les yeux du test.
     */
    private class Espion(private val refuse: Boolean = false) : SystemCopyEraser {
        val presentes = mutableListOf<String?>()
        override fun erase(message: MessageEntity): Boolean {
            presentes += message.telephonyUri ?: message.mmsSystemId?.let { "content://mms/$it" }
            return !refuse
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            db.conversationDao().insert(
                ConversationEntity(
                    id = CONV_ID,
                    threadId = CONV_ID,
                    addressesCsv = ADRESSE,
                    displayName = null,
                    lastMessagePreview = "",
                    lastMessageAt = 0L,
                    unreadCount = 0,
                ),
            )
        }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun laPurgeDeRetentionPresenteAuFournisseurExactementLesMessagesQuElleEfface() =
        runBlocking<Unit> {
            insere(uri = "content://sms/1", date = VIEUX)
            insere(uri = "content://sms/2", date = VIEUX)
            insere(uri = "content://sms/3", date = RECENT) // hors du champ de la purge
            val espion = Espion()

            val efface = eraserAvec(espion).purgeHistory(CUTOFF)

            assertThat(efface).isEqualTo(2)
            // LE point du test : sans propagation, cette liste serait vide et la base tout aussi
            // vide — le defaut etait exactement cet ecart-la.
            assertThat(espion.presentes).containsExactly("content://sms/1", "content://sms/2")
            assertThat(db.messageDao().findByConversation(CONV_ID).map { it.telephonyUri })
                .containsExactly("content://sms/3")
        }

    /**
     * Le filet de securite du DAO : un favori ne se purge pas. Il ne doit donc pas non plus etre
     * presente au fournisseur — deux criteres de selection qui divergeraient feraient effacer du
     * systeme un message qui reste en base.
     */
    @Test
    fun unFavoriNEstNiEffaceNiPresenteAuFournisseur() = runBlocking<Unit> {
        insere(uri = "content://sms/10", date = VIEUX, starred = true)
        insere(uri = "content://sms/11", date = VIEUX)
        val espion = Espion()

        val efface = eraserAvec(espion).purgeHistory(CUTOFF)

        assertThat(efface).isEqualTo(1)
        assertThat(espion.presentes).containsExactly("content://sms/11")
        assertThat(db.messageDao().findByConversation(CONV_ID).map { it.telephonyUri })
            .containsExactly("content://sms/10")
    }

    /**
     * Un message jamais miroite dans le systeme n'a rien a y faire disparaitre : il est ecarte
     * **par la requete**, et non charge pour rien. Il doit malgre tout etre efface en base.
     */
    @Test
    fun unMessageSansLiaisonSysteme_estEffaceSansEtrePresente() = runBlocking<Unit> {
        insere(uri = null, date = VIEUX)
        insere(uri = "content://sms/20", date = VIEUX)
        val espion = Espion()

        val efface = eraserAvec(espion).purgeHistory(CUTOFF)

        assertThat(efface).isEqualTo(2)
        assertThat(espion.presentes).containsExactly("content://sms/20")
        assertThat(db.messageDao().findByConversation(CONV_ID)).isEmpty()
    }

    /**
     * La pagination par cle doit couvrir **tout** le lot, pas seulement la premiere page. Avec
     * une page de 200, 450 messages en font trois — dont une incomplete. Une boucle qui
     * s'arreterait a la premiere laisserait 250 copies systeme derriere elle, en silence.
     */
    @Test
    fun laPaginationCouvreToutLeLotEtPasSeulementLaPremierePage() = runBlocking<Unit> {
        repeat(NOMBREUX) { insere(uri = "content://sms/${1000 + it}", date = VIEUX) }
        val espion = Espion()

        val efface = eraserAvec(espion).purgeHistory(CUTOFF)

        assertThat(efface).isEqualTo(NOMBREUX)
        assertThat(espion.presentes).hasSize(NOMBREUX)
        assertThat(espion.presentes.toSet()).hasSize(NOMBREUX) // aucun doublon, aucun oubli
        assertThat(db.messageDao().findByConversation(CONV_ID)).isEmpty()
    }

    /**
     * Le contrat qui distingue cette purge de celle du coffre : **la ligne locale part quand meme**.
     * La regle est *« la ligne locale ne survit a un echec de propagation que si une decision de
     * SECURITE en depend »* — retirer le PIN du coffre en est une, une purge de retention n'en
     * leve aucune. Sans ce test, un correctif zele alignerait les deux et enfermerait
     * l'utilisateur dans un historique qu'il a demande a voir disparaitre.
     */
    @Test
    fun unRefusDuFournisseurNEmpechePasLEffacementLocal() = runBlocking<Unit> {
        insere(uri = "content://sms/30", date = VIEUX)
        val espion = Espion(refuse = true)

        val efface = eraserAvec(espion).purgeHistory(CUTOFF)

        assertThat(efface).isEqualTo(1)
        assertThat(espion.presentes).containsExactly("content://sms/30")
        assertThat(db.messageDao().findByConversation(CONV_ID)).isEmpty()
    }

    // ─────────────────────── v1.28.3 — F10 et F11 ───────────────────────

    /**
     * v1.28.3 (F10) — **un MMS sortant n'a jamais de `telephony_uri`.**
     *
     * `ConversationMirror.upsertOutgoingMms` et `upsertOutgoingMediaMms` ecrivent tous deux
     * `telephonyUri = null` ; le seul lien vers le fournisseur est `mms_system_id`. La requete de
     * propagation filtrait sur `telephony_uri IS NOT NULL` : **aucun MMS envoye par
     * l'application n'a jamais ete presente au fournisseur par la retention**. Ils restaient dans
     * `content://mms`, et une resynchronisation complete les ramenait.
     */
    @Test
    fun unMmsSortantEstPresenteAuFournisseurMalgreLAbsenceDeTelephonyUri() = runBlocking<Unit> {
        insere(uri = null, date = VIEUX, mmsSystemId = 77L)
        insere(uri = "content://sms/40", date = VIEUX)
        val espion = Espion()

        val efface = eraserAvec(espion).purgeHistory(CUTOFF)

        assertThat(efface).isEqualTo(2)
        assertThat(espion.presentes)
            .containsExactly("content://mms/77", "content://sms/40")
        assertThat(db.messageDao().findByConversation(CONV_ID)).isEmpty()
    }

    /**
     * v1.28.3 (F11) — **la retention n'entre plus dans le coffre.**
     *
     * Elle etait la seule ecriture destructrice de ce fichier a ne pas l'exclure, alors que cinq
     * autres requetes le font. Et le contrat de cette purge — la ligne locale part quoi qu'il
     * arrive — rendait la chose pire que la suppression elle-meme : quand la copie systeme
     * resistait, le lien disparaissait avec la ligne, et la resynchronisation suivante
     * reimportait le message HORS du coffre, dans une conversation ordinaire. Un contenu protege
     * ressuscite en clair.
     */
    @Test
    fun laRetentionNEffacePasLesMessagesDuCoffre() = runBlocking<Unit> {
        creeConversationDuCoffre()
        insere(uri = "content://sms/50", date = VIEUX, conversationId = CONV_COFFRE)
        insere(uri = "content://sms/51", date = VIEUX)
        val espion = Espion()

        val efface = eraserAvec(espion).purgeHistory(CUTOFF)

        assertThat(efface).isEqualTo(1)
        // Ni efface, ni meme PRESENTE au fournisseur : les deux criteres doivent concorder.
        assertThat(espion.presentes).containsExactly("content://sms/51")
        assertThat(db.messageDao().findByConversation(CONV_COFFRE)).hasSize(1)
        assertThat(db.messageDao().findByConversation(CONV_ID)).isEmpty()
    }

    /**
     * Le nombre montre a l'utilisateur avant qu'il confirme doit decrire EXACTEMENT ce que la
     * purge effacera. Sans ce test, le compteur promettrait l'effacement de messages du coffre
     * que la purge ne touche plus — une divergence invisible entre une promesse et un acte.
     */
    @Test
    fun leCompteurAnnonceExactementCeQueLaPurgeEfface() = runBlocking<Unit> {
        creeConversationDuCoffre()
        insere(uri = "content://sms/60", date = VIEUX, conversationId = CONV_COFFRE)
        insere(uri = "content://sms/61", date = VIEUX)
        insere(uri = "content://sms/62", date = VIEUX, starred = true)

        val annonce = db.messageDao().countOlderThan(CUTOFF)
        val efface = eraserAvec(Espion()).purgeHistory(CUTOFF)

        assertThat(annonce).isEqualTo(1)
        assertThat(efface).isEqualTo(annonce)
    }

    /**
     * v1.28.7 — **la purge de retention annule les notifications des messages qu'elle efface, et
     * de ceux-la seulement.** Elle faisait un DELETE de masse sans les annuler : un message efface
     * restait lisible dans le volet.
     *
     * Les exclusions sont celles du DELETE, mot pour mot — un favori, un message recent et un
     * message du coffre restent en base, donc leurs notifications aussi. Deux criteres qui
     * divergeraient annuleraient la notification d'un message conserve.
     */
    @Test
    fun laPurgeDeRetentionAnnuleLesNotificationsDesSeulsMessagesEffaces() = runBlocking<Unit> {
        creeConversationDuCoffre()
        val vieux = insere(uri = "content://sms/70", date = VIEUX)
        val vieuxSansLiaison = insere(uri = null, date = VIEUX)
        insere(uri = "content://sms/71", date = VIEUX, starred = true)
        insere(uri = "content://sms/72", date = RECENT)
        insere(uri = "content://sms/73", date = VIEUX, conversationId = CONV_COFFRE)
        val annulateur = AnnulateurDeNotificationsEspion()

        eraserAvec(Espion(), annulateur).purgeHistory(CUTOFF)

        assertThat(annulateur.lots).hasSize(1)
        assertThat(annulateur.lots.single().keys).containsExactly(CONV_ID)
        assertThat(annulateur.lots.single().getValue(CONV_ID)).containsExactly(vieux, vieuxSansLiaison)
    }

    /** Rien d'efface, rien a annuler : aucun appel, pas meme un lot vide. */
    @Test
    fun uneRetentionQuiNEffaceRienNAnnuleRien() = runBlocking<Unit> {
        insere(uri = "content://sms/80", date = RECENT)
        val annulateur = AnnulateurDeNotificationsEspion()

        eraserAvec(Espion(), annulateur).purgeHistory(CUTOFF)

        assertThat(annulateur.lots).isEmpty()
    }

    private fun eraserAvec(
        systemCopy: SystemCopyEraser,
        annulateur: AnnulateurDeNotificationsEspion = AnnulateurDeNotificationsEspion(),
    ) =
        ConversationEraser(
            db,
            db.conversationDao(),
            db.messageDao(),
            systemCopy,
            // v1.28.3 (F03/F04) — la suppression emporte desormais les envois programmes et les
            // FICHIERS des pieces jointes. Ces tests-ci ne les exercent pas : les DAO reels de la
            // base en memoire rendent des listes vides, et l'ordonnanceur factice ne fait rien.
            db.scheduledMessageDao(),
            object : com.filestech.sms.domain.scheduler.ScheduledMessageScheduler {
                override fun scheduleAt(scheduledMessageId: Long, epochMillis: Long) = Unit
                override fun cancel(scheduledMessageId: Long) = Unit
            },
            db.attachmentDao(),
            InstrumentationRegistry.getInstrumentation().targetContext,
            com.filestech.sms.security.VaultPurgeBarrier(),
            // v1.28.6 — l'effaceur annule les notifications de ce qu'il supprime ;
            // v1.28.7 — la purge de retention aussi, et ce fichier le tient.
            annulateur,
        )

    private suspend fun insere(
        uri: String?,
        date: Long,
        starred: Boolean = false,
        conversationId: Long = CONV_ID,
        mmsSystemId: Long? = null,
    ): Long {
        return db.messageDao().insert(
            MessageEntity(
                conversationId = conversationId,
                telephonyUri = uri,
                address = ADRESSE,
                body = "message",
                type = MessageType.SMS,
                direction = MessageDirection.INCOMING,
                date = date,
                dateSent = date,
                read = true,
                starred = starred,
                status = MessageStatus.RECEIVED,
                errorCode = null,
                subId = null,
                scheduledAt = null,
                attachmentsCount = 0,
                mmsSystemId = mmsSystemId,
            ),
        )
    }

    /** Conversation du coffre, creee a la demande par les tests F11. */
    private suspend fun creeConversationDuCoffre() {
        db.conversationDao().insert(
            ConversationEntity(
                id = CONV_COFFRE,
                threadId = CONV_COFFRE,
                addressesCsv = "+33699999999",
                displayName = null,
                lastMessagePreview = "",
                lastMessageAt = 0L,
                unreadCount = 0,
                inVault = true,
            ),
        )
    }

    private companion object {
        const val CONV_ID = 1L
        const val ADRESSE = "+33612345678"
        const val CUTOFF = 1_700_000_000_000L
        const val VIEUX = 1_600_000_000_000L
        const val RECENT = 1_800_000_000_000L

        const val CONV_COFFRE = 2L

        /** Plus de deux pages de 200 : la troisieme est volontairement incomplete. */
        const val NOMBREUX = 450
    }
}
