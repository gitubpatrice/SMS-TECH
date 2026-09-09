package com.filestech.sms.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.core.crypto.AeadCipher
import com.filestech.sms.core.crypto.PasswordKdf
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.datastore.SecurityStore
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.filestech.sms.security.AppLockManager
import com.filestech.sms.security.VaultSessionState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * v1.27.2 — **aller-retour complet d'une sauvegarde `.smsbk`**, sur base Room réelle.
 *
 * Ce test manquait, et c'est le seul chemin de l'application où un défaut se paie en **perte
 * définitive** : une sauvegarde qui s'écrit mal ne se découvre qu'au moment de la restaurer,
 * c'est-à-dire quand l'original n'existe plus.
 *
 * # Pourquoi en `androidTest` et pas en JVM
 *
 * `BackupService` prend une `AppDatabase` et déroule tout le pipeline réel — PBKDF2, AES-GCM,
 * `ContentResolver`, transaction Room. SQLCipher étant une bibliothèque **native**, rien de tout
 * cela ne se monte sur une JVM de bureau (cf. `HiltRobolectricSmokeTest`). Exécuté sur le Galaxy
 * S9.
 *
 * # Le montage : DEUX bases
 *
 * Une restauration réelle vise une installation **neuve**. Le test exporte donc depuis `dbSource`
 * et importe dans `dbTarget`, deux bases distinctes — sans quoi l'import retomberait sur ses
 * propres lignes via `findByAddressesCsv` et le test serait vacant.
 *
 * ⚠️ JUnit 4 : les méthodes doivent rendre `Unit`. `fun f() = runBlocking { … }` rend la valeur du
 * bloc et fait échouer la classe entière à l'initialisation.
 */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {

    private lateinit var dbSource: AppDatabase
    private lateinit var dbTarget: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var vaultSource: VaultSessionState
    private lateinit var backupFile: File

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val ADDR_A = "+33611111111"
        const val ADDR_B = "+33622222222"
        const val BODY_1 = "Premier message"
        const val BODY_2 = "Deuxieme message"
        const val BODY_VAULT = "Message du coffre"
        val PASSWORD get() = "correct horse battery staple".toCharArray()
    }

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO)
        vaultSource = VaultSessionState()
        dbSource = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dbTarget = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        backupFile = File(context.cacheDir, "roundtrip-test.smsbk").apply { delete() }
    }

    @After
    fun tearDown() {
        dbSource.close()
        dbTarget.close()
        backupFile.delete()
        scope.cancel()
    }

    // ──────────────────────── Le contrat principal ────────────────────────

    @Test
    fun uneSauvegardeRestauree_rendLesMemesConversationsEtLesMemesMessages() {
        runBlocking {
            seedSource()
            // Le jeu d'essai contient une conversation du coffre : sans session ouverte, l'export
            // est REFUSE (garde v1.27.2, exercé plus bas). Ma première version l'oubliait et ce
            // test échouait — le garde fonctionne.
            vaultSource.markUnlocked()

            val written = serviceFor(dbSource, vaultSource).writeSmsbk(uriOf(backupFile), PASSWORD)
            assertThat(written).isInstanceOf(Outcome.Success::class.java)
            assertThat(backupFile.exists()).isTrue()
            assertThat(backupFile.length()).isGreaterThan(0L)

            val restored = serviceFor(dbTarget, VaultSessionState())
                .readSmsbk(uriOf(backupFile), PASSWORD)
            assertThat(restored).isInstanceOf(Outcome.Success::class.java)

            val result = (restored as Outcome.Success).value
            // Base cible vierge : tout doit avoir ete CREE, rien reutilise.
            assertThat(result.conversationsCreated).isEqualTo(2)
            assertThat(result.conversationsReused).isEqualTo(0)
            assertThat(result.messagesImported).isEqualTo(3)
            assertThat(result.messagesSkipped).isEqualTo(0)

            // Et surtout : le CONTENU, pas seulement les compteurs. Un import qui creerait les
            // bonnes lignes avec les mauvais corps satisferait le bilan ci-dessus.
            val convA = dbTarget.conversationDao().findByAddressesCsv(ADDR_A)
            val convB = dbTarget.conversationDao().findByAddressesCsv(ADDR_B)
            assertThat(convA).isNotNull()
            assertThat(convB).isNotNull()

            // v1.28.3 (F01) — la restauration ne reprend JAMAIS le `thread_id` de la sauvegarde,
            // meme quand elle en porte un : il designe un fil du fournisseur de l'appareil SOURCE
            // et peut, ici, pointer une autre conversation — c'est le defaut que la v1.27.11 a
            // ferme sur `telephony_uri`. `TelephonySyncWorker` reassignera le vrai fil.
            //
            // La conversation A portait `thread_id = 1` a la source, la B `null` : les deux
            // ressortent a `null`, ce qui verrouille du meme coup la disparition des
            // placeholders negatifs que cette methode fabriquait depuis la v1.15.2.
            assertThat(convA!!.threadId).isNull()
            assertThat(convB!!.threadId).isNull()

            // ⚠️ La premiere version s'arretait a `findConversationIdsByTelephonyUris`, qui rend
            // des IDENTIFIANTS de conversation — pas des corps, malgre le nom que j'avais donne a
            // la variable (relecture Codex du 2026-08-05, finding 2). Une sauvegarde qui aurait
            // conserve les URI en vidant tous les corps passait le test.
            //
            // On compare donc les couples (telephonyUri, body) un a un.
            val restoredBodies = dbTarget.messageDao().listAll()
                .associate { it.telephonyUri to it.body }
            assertThat(restoredBodies).containsExactlyEntriesIn(
                mapOf(
                    "content://sms/1" to BODY_1,
                    "content://sms/2" to BODY_2,
                    "content://sms/3" to BODY_VAULT,
                ),
            )

            // Et le remappage : les 3 messages doivent rester repartis sur DEUX conversations,
            // celles-la memes, pas melanges.
            val all = dbTarget.messageDao().listAll()
            val byUri = all.associateBy { it.telephonyUri }
            assertThat(byUri.getValue("content://sms/1").conversationId)
                .isEqualTo(byUri.getValue("content://sms/2").conversationId)
            assertThat(byUri.getValue("content://sms/3").conversationId)
                .isNotEqualTo(byUri.getValue("content://sms/1").conversationId)
        }
    }

    /**
     * Le coffre doit **rester dans le coffre** après restauration.
     *
     * C'est le correctif H12 : `findByAddressesCsv` ne filtre pas `in_vault`, si bien qu'une
     * conversation protégée pouvait être fusionnée dans son homonyme EN CLAIR et redevenir
     * visible — y compris en mode leurre. Le test le fige côté restauration.
     *
     * ⚠️ **Ce test était VACANT dans sa première version** (relecture Gemini du 2026-08-05,
     * finding 1). Il restaurait dans une base cible **vide** : `findByAddressesCsv` y rendait
     * toujours `null`, la garde `takeIf { it.inVault == backupConv.inVault }` n'était jamais
     * atteinte, et le test se contentait de constater qu'une entité avait été recopiée telle
     * quelle. Il serait resté vert avec le correctif H12 retiré.
     *
     * Il faut donc **l'homonyme**. La base cible reçoit d'abord une conversation portant la MÊME
     * adresse mais **hors du coffre** — exactement le cas d'une réinstallation suivie d'une
     * synchronisation système, qui est le scénario d'origine du défaut. C'est cette ligne-là qui
     * arme la garde.
     */
    @Test
    fun uneConversationDuCoffre_resteDansLeCoffreApresRestauration() {
        runBlocking {
            seedSource()
            // Session ouverte : l'export d'un coffre non vide est autorise.
            vaultSource.markUnlocked()

            val written = serviceFor(dbSource, vaultSource).writeSmsbk(uriOf(backupFile), PASSWORD)
            assertThat(written).isInstanceOf(Outcome.Success::class.java)

            // L'HOMONYME EN CLAIR : sans lui, la garde n'est jamais exercee.
            dbTarget.conversationDao().insert(conversation(99L, ADDR_B, inVault = false))
            assertThat(dbTarget.conversationDao().countInVault()).isEqualTo(0)

            serviceFor(dbTarget, VaultSessionState()).readSmsbk(uriOf(backupFile), PASSWORD)

            // Une conversation de coffre a bien ete CREEE a cote de l'homonyme en clair, au lieu
            // d'etre fusionnee dedans.
            assertThat(dbTarget.conversationDao().countInVault()).isEqualTo(1)

            // L'assertion qui porte reellement la preuve : le message du coffre doit avoir atterri
            // dans une conversation `inVault`, PAS dans l'homonyme en clair prealablement pose.
            // Sans la garde, il rejoindrait la conversation en clair et cette assertion tomberait.
            // ⚠️ v1.27.2 (audit Codex, LP-03) — NE PAS passer par
            // `findConversationIdsByTelephonyUris` ici : cette requete est desormais
            // VOLONTAIREMENT AVEUGLE AU COFFRE (`AND conversation_id IN (... WHERE in_vault = 0)`),
            // pour que la reconciliation des suppressions ne puisse jamais effacer un message
            // protege. Lui demander l'identifiant d'un message du Coffre ne rend donc rien — et
            // c'est correct. C'est le test qui se servait du mauvais outil, pas le produit qui
            // regressait.
            val landed = dbTarget.messageDao().findByTelephonyUri("content://sms/3")
            assertThat(landed).isNotNull()
            val landedIn = dbTarget.conversationDao().findById(landed!!.conversationId)
            assertThat(landedIn).isNotNull()
            assertThat(landedIn!!.inVault).isTrue()
            assertThat(landedIn.id).isNotEqualTo(99L)
        }
    }

    // ──────────────────────── Les replis, et leur sens ────────────────────────

    /**
     * Un mauvais mot de passe ne doit **rien** importer — pas « importer partiellement ».
     *
     * Une restauration à moitié faite est pire qu'une restauration refusée : elle laisse une base
     * dans un état que l'utilisateur croit complet.
     */
    @Test
    fun unMauvaisMotDePasse_nImportePasUneSeuleLigne() {
        runBlocking {
            seedSource()
            vaultSource.markUnlocked()
            serviceFor(dbSource, vaultSource).writeSmsbk(uriOf(backupFile), PASSWORD)

            val restored = serviceFor(dbTarget, VaultSessionState())
                .readSmsbk(uriOf(backupFile), "mauvais mot de passe".toCharArray())

            assertThat(restored).isInstanceOf(Outcome.Failure::class.java)
            assertThat(dbTarget.conversationDao().findByAddressesCsv(ADDR_A)).isNull()
            assertThat(dbTarget.conversationDao().findByAddressesCsv(ADDR_B)).isNull()
            assertThat(dbTarget.conversationDao().countInVault()).isEqualTo(0)
        }
    }

    /**
     * v1.27.2 (audit externe 2026-08-04 #4), reformule en v1.28.0 — le second facteur du Coffre
     * garde aussi l'EXPORT, **quand il en existe un**.
     *
     * Sans ce garde, quiconque passait le verrou principal repartait avec l'integralite du coffre
     * dans un fichier dechiffrable **hors de l'appareil**, avec la passphrase de son choix : le
     * second facteur etait contourne, et definitivement une fois le fichier copie.
     *
     * Le test verifie les deux moities — le refus, ET l'absence de fichier exploitable. Un refus
     * qui aurait quand meme ecrit les octets ne protegerait rien.
     *
     * ⚠️ Il POSE le PIN de coffre lui-meme, et le retire dans un `finally`. La version anterieure
     * de ce test ne le faisait pas : elle dependait des reglages reels de l'application de test,
     * et passait ou echouait selon l'etat laisse par une session precedente. Sur un appareil ou
     * un PIN avait ete configure a la main, elle passait ; sur une installation neuve, elle
     * echouait. Un test de securite ne doit pas dependre de ce qu'on a bricole avant lui.
     */
    @Test
    fun coffreAvecSecondFacteur_refuseLExport_etNEcritAucunFichierExploitable() {
        runBlocking {
            seedSource()
            // vaultSource reste VERROUILLE, et la base source contient une conv du coffre.
            assertThat(dbSource.conversationDao().countInVault()).isEqualTo(1)

            val pinManager = vaultPinManager()
            assertThat(pinManager.configureVaultPin("246810".toCharArray())).isTrue()
            try {
                // Premisse EXPLICITE : sans elle, ce test affirmerait le refus sur une
                // configuration ou il n'y a rien a prouver, et passerait pour une mauvaise raison.
                assertThat(vaultFactorPolicy().current())
                    .isEqualTo(com.filestech.sms.security.VaultSecondFactor.PIN)

                val written =
                    serviceFor(dbSource, vaultSource).writeSmsbk(uriOf(backupFile), PASSWORD)

                assertThat(written).isInstanceOf(Outcome.Failure::class.java)
                assertThat((written as Outcome.Failure).error)
                    .isInstanceOf(AppError.Locked::class.java)
                // Rien d'exploitable : soit aucun fichier, soit un fichier vide.
                assertThat(backupFile.exists() && backupFile.length() > 0L).isFalse()
            } finally {
                pinManager.forgetVaultPin()
            }
        }
    }

    /**
     * v1.28.0 — **la decision**, et non un simple constat : un coffre SANS second facteur
     * n'empeche pas l'export.
     *
     * La garde de la v1.27.2 refusait des que le coffre n'etait pas vide, quelle que soit la
     * configuration. Elle exigeait donc de prouver un facteur que ces utilisateurs n'ont jamais
     * pose — ce qui ne les protegeait pas, et cassait leur sauvegarde. Sans PIN de coffre,
     * ouvrir le coffre ne demande rien : le coffre est de la DISSIMULATION, pas du controle
     * d'acces, et l'export herite du niveau que l'utilisateur a choisi.
     *
     * Objection connue, tranchee sciemment : lire n'est pas exfiltrer, et un fichier chiffre
     * emporte vaut plus qu'une lecture sur place. C'est le prix assume de ne pas reclamer la
     * preuve d'un facteur inexistant. Qui veut cette protection pose un PIN de coffre, et le
     * test ci-dessus verifie qu'elle s'applique alors.
     */
    @Test
    fun coffreSansSecondFacteur_exporteSansFriction() {
        runBlocking {
            seedSource()
            assertThat(dbSource.conversationDao().countInVault()).isEqualTo(1)
            // Premisse EXPLICITE, pour la meme raison que ci-dessus, en sens inverse.
            assertThat(vaultFactorPolicy().current())
                .isEqualTo(com.filestech.sms.security.VaultSecondFactor.NONE)

            val written = serviceFor(dbSource, vaultSource).writeSmsbk(uriOf(backupFile), PASSWORD)

            assertThat(written).isInstanceOf(Outcome.Success::class.java)
            assertThat(backupFile.length()).isGreaterThan(0L)
        }
    }

    /**
     * Non-vacuité du garde ci-dessus : sans conversation dans le coffre, l'export doit rester sans
     * friction. Un garde qui refuserait TOUT export passerait le test précédent sans rien prouver.
     */
    @Test
    fun coffreVide_lExportResteSansFriction_memeVerrouille() {
        runBlocking {
            dbSource.conversationDao().insert(conversation(1L, ADDR_A, inVault = false))
            dbSource.messageDao().insert(message(1L, 1L, "content://sms/1", BODY_1))
            assertThat(dbSource.conversationDao().countInVault()).isEqualTo(0)

            val written = serviceFor(dbSource, vaultSource).writeSmsbk(uriOf(backupFile), PASSWORD)

            assertThat(written).isInstanceOf(Outcome.Success::class.java)
            assertThat(backupFile.length()).isGreaterThan(0L)
        }
    }

    // ──────────────────────── Montage ────────────────────────

    /** Deux conversations : une ordinaire (2 messages), une dans le coffre (1 message). */
    private suspend fun seedSource() {
        dbSource.conversationDao().insert(conversation(1L, ADDR_A, inVault = false))
        // v1.28.3 (F01) — sans fil systeme : exerce le `null` a la serialisation.
        dbSource.conversationDao().insert(conversation(2L, ADDR_B, inVault = true, threadId = null))
        dbSource.messageDao().insert(message(1L, 1L, "content://sms/1", BODY_1))
        dbSource.messageDao().insert(message(2L, 1L, "content://sms/2", BODY_2))
        dbSource.messageDao().insert(message(3L, 2L, "content://sms/3", BODY_VAULT))
    }

    private fun uriOf(file: File): Uri = Uri.fromFile(file)

    /**
     * `BackupService` est construit à la main plutôt qu'injecté : Hilt fournirait la base de
     * PRODUCTION, et un test qui exporte la vraie base de l'appareil serait à la fois inutile et
     * dangereux.
     */
    private fun serviceFor(db: AppDatabase, vault: VaultSessionState) = BackupService(
        context = context,
        database = db,
        conversationDao = db.conversationDao(),
        messageDao = db.messageDao(),
        kdf = PasswordKdf(),
        aead = AeadCipher(),
        // État initial `Locked`, jamais `PanicDecoy` : le garde anti-leurre laisse donc passer,
        // et c'est bien le garde du COFFRE que ces tests exercent.
        appLock = AppLockManager(
            securityStore = SecurityStore(context),
            settings = SettingsRepository(context, scope),
            kdf = PasswordKdf(),
            // v1.27.2 — `forceLock` verrouille désormais aussi le Coffre. Ces tests n'appellent
            // pas `forceLock`, mais le constructeur l'exige : on passe la MÊME session que celle
            // du service, sans quoi le test exercerait deux états distincts.
            vaultSession = vault,
            io = Dispatchers.IO,
        ),
        vaultSession = vault,
        vaultFactor = vaultFactorPolicy(),
        io = Dispatchers.IO,
    )

    /**
     * v1.28.0 — construite sur les MEMES reglages et le MEME magasin securise que le service.
     *
     * Les tests qui dependent de sa reponse la verifient explicitement avant d'affirmer quoi que
     * ce soit : elle lit l'etat REEL de l'application de test, donc une session precedente peut
     * l'avoir changee.
     */
    private fun vaultFactorPolicy() = com.filestech.sms.security.VaultSecondFactorPolicy(
        settings = SettingsRepository(context, scope),
        vaultPin = vaultPinManager(),
        io = Dispatchers.IO,
    )

    private fun vaultPinManager() = com.filestech.sms.security.VaultPinManager(
        securityStore = SecurityStore(context),
        settings = SettingsRepository(context, scope),
        kdf = PasswordKdf(),
        io = Dispatchers.IO,
    )

    /**
     * v1.28.3 (F01) — [threadId] est parametrable et vaut `null` par defaut sur l'une des
     * conversations du jeu d'essai : c'est le seul moyen d'exercer la serialisation JSON d'un
     * `thread_id` ABSENT de bout en bout (ecriture chiffree, relecture, reinsertion). La colonne
     * etant devenue nullable, une sauvegarde produite par cette version en contient desormais
     * couramment.
     */
    private fun conversation(
        id: Long,
        address: String,
        inVault: Boolean,
        threadId: Long? = id,
    ) = ConversationEntity(
        id = id,
        threadId = threadId,
        addressesCsv = address,
        displayName = null,
        lastMessageAt = id * 1_000L,
        lastMessagePreview = "apercu $id",
        unreadCount = 0,
        inVault = inVault,
    )

    private fun message(id: Long, convId: Long, uri: String, body: String) = MessageEntity(
        id = id,
        conversationId = convId,
        telephonyUri = uri,
        address = "+3360000000$convId",
        body = body,
        type = MessageType.SMS,
        direction = MessageDirection.INCOMING,
        date = id * 1_000L,
        dateSent = null,
        read = true,
        starred = false,
        status = MessageStatus.DELIVERED,
        attachmentsCount = 0,
    )
}
