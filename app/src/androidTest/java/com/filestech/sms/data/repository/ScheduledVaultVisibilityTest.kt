package com.filestech.sms.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.core.crypto.PasswordKdf
import com.filestech.sms.data.local.datastore.SecurityStore
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.ScheduledMessageEntity
import com.filestech.sms.security.AppLockManager
import com.filestech.sms.security.VaultSessionState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v1.28.3 (F02) — **le contenu du coffre se lisait par l'écran « Messages programmés ».**
 *
 * `ScheduledMessageDao.observePending` et `observeFailed` étaient deux `SELECT * FROM
 * scheduled_messages` sans le moindre filtre, là où toutes les requêtes de `ConversationDao`
 * portent `WHERE in_vault = 0`. Un message programmé depuis une conversation du coffre y
 * affichait donc son corps et ses destinataires en clair, sans que le second facteur ait été
 * franchi — et y compris en session leurre, où la conversation d'origine, elle, reste invisible.
 *
 * La règle appliquée est mot pour mot celle de `ConversationRepositoryImpl.observeOne`, et c'est
 * volontaire : deux règles de visibilité du coffre finiraient par diverger, et c'est exactement
 * ce qui a produit ce défaut.
 *
 * [unEnvoiHorsCoffreResteToujoursVisible] est le contrôle positif sans lequel ce fichier ne
 * prouverait rien : un filtre qui masquerait TOUT passerait les deux premiers tests tout en
 * cassant la fonctionnalité.
 */
@RunWith(AndroidJUnit4::class)
class ScheduledVaultVisibilityTest {

    private lateinit var db: AppDatabase
    private lateinit var vaultSession: VaultSessionState
    private lateinit var repo: ScheduledMessageRepositoryImpl
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        vaultSession = VaultSessionState()
        val appLock = AppLockManager(
            SecurityStore(context),
            SettingsRepository(context, scope),
            PasswordKdf(),
            vaultSession,
            // v1.28.3 (F07) — cf. `BackupRoundTripTest` : ces tests n'abaissent pas le
            // verrouillage, mais le constructeur exige desormais de quoi le garder.
            {
                com.filestech.sms.security.VaultSecondFactorPolicy(
                    SettingsRepository(context, scope),
                    com.filestech.sms.security.VaultPinManager(
                        SecurityStore(context),
                        SettingsRepository(context, scope),
                        PasswordKdf(),
                        Dispatchers.IO,
                    ),
                    Dispatchers.IO,
                )
            },
            { db.conversationDao() },
            Dispatchers.IO,
        )
        repo = ScheduledMessageRepositoryImpl(
            db.scheduledMessageDao(),
            appLock,
            vaultSession,
            Dispatchers.IO,
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun unEnvoiProgrammeDuCoffreEstMasqueTantQueLeCoffreEstFerme(): Unit = runBlocking {
        seed(conversationId = COFFRE, inVault = true, corps = "rendez-vous secret")

        // Le coffre n'a pas ete ouvert dans cette session : rien ne doit sortir.
        assertThat(repo.observePending().first()).isEmpty()
    }

    @Test
    fun leMemeEnvoiApparaitUneFoisLeCoffreOuvert(): Unit = runBlocking {
        seed(conversationId = COFFRE, inVault = true, corps = "rendez-vous secret")

        vaultSession.markUnlocked()

        val visibles = repo.observePending().first()
        assertThat(visibles).hasSize(1)
        assertThat(visibles.single().body).isEqualTo("rendez-vous secret")
    }

    /**
     * Contrôle positif. Le filtre ne doit toucher qu'au coffre — un envoi programmé ordinaire
     * reste visible sans qu'on demande quoi que ce soit.
     */
    @Test
    fun unEnvoiHorsCoffreResteToujoursVisible(): Unit = runBlocking {
        seed(conversationId = ORDINAIRE, inVault = false, corps = "courses de demain")

        val visibles = repo.observePending().first()
        assertThat(visibles).hasSize(1)
        assertThat(visibles.single().body).isEqualTo("courses de demain")
    }

    /**
     * `scheduled_messages.conversation_id` est nullable et n'est pas une clé étrangère : un envoi
     * peut n'avoir aucune conversation. Le `LEFT JOIN` rend alors `NULL`, que `COALESCE` ramène à
     * « hors coffre ». Sans cela le drapeau serait indéterminé et la ligne pourrait disparaître
     * sans raison.
     */
    @Test
    fun unEnvoiSansConversationResteVisible(): Unit = runBlocking {
        db.scheduledMessageDao().upsert(
            ScheduledMessageEntity(
                conversationId = null,
                addressesCsv = "+33600000001",
                body = "sans conversation",
                scheduledAt = System.currentTimeMillis() + 3_600_000L,
                createdAt = System.currentTimeMillis(),
            ),
        )

        assertThat(repo.observePending().first()).hasSize(1)
    }

    /**
     * v1.28.3 (F20) — **la règle d'affichage ne doit pas décider de ce qui part.**
     *
     * Le filet de replanification du démarrage
     * (`ScheduledMessageSchedulerImpl.rescheduleAllPending`) lisait `observePending()`, c'est-à-dire
     * le flux destiné à l'écran. Depuis le correctif F02 ci-dessus, celui-ci masque les envois du
     * coffre tant que le second facteur n'a pas été donné — ce qui, au démarrage, est toujours le
     * cas. Un envoi programmé depuis une conversation protégée cessait donc d'être rattrapé quand
     * WorkManager avait perdu son job : le message ne partait jamais, sans un mot.
     *
     * Une correction de confidentialité venait de créer une perte de données silencieuse, sur le
     * chemin voisin. `allUnsettled` est la lecture non masquée que ce filet-là exige.
     */
    @Test
    fun leFiletDeReplanificationVoitLesEnvoisDuCoffreCoffreFerme(): Unit = runBlocking {
        seed(conversationId = COFFRE, inVault = true, corps = "rendez-vous secret")
        seed(conversationId = ORDINAIRE, inVault = false, corps = "courses de demain")

        // Coffre fermé — c'est l'état du démarrage.
        assertThat(repo.observePending().first().map { it.body })
            .containsExactly("courses de demain")
        assertThat(repo.allUnsettled().map { it.body })
            .containsExactly("rendez-vous secret", "courses de demain")
    }

    /**
     * Le même filet doit réveiller une ligne **revendiquée puis abandonnée** (`SENDING`), sans
     * quoi personne ne constaterait l'expiration de son bail et elle resterait bloquée à vie.
     * C'est la moitié « boot » de F20 ; l'autre vit dans `ScheduledSendAttemptTest`.
     */
    @Test
    fun leFiletDeReplanificationVoitAussiUnEnvoiRevendique(): Unit = runBlocking {
        seed(conversationId = ORDINAIRE, inVault = false, corps = "interrompu")
        val id = repo.allUnsettled().single().id
        assertThat(db.scheduledMessageDao().claimForSending(id, now = 1_000L)).isEqualTo(1)

        assertThat(repo.allUnsettled().map { it.body }).containsExactly("interrompu")
    }

    /**
     * Contrôle positif du bail, côté SQL. Sans lui, une requête qui conclurait tout — ou rien —
     * passerait le test précédent. Les deux conditions sont vérifiées dans le même mouvement :
     * un bail encore valide protège la ligne, un bail expiré la conclut.
     */
    @Test
    fun seuleUneRevendicationExpireeEstConclue(): Unit = runBlocking {
        seed(conversationId = ORDINAIRE, inVault = false, corps = "en vol")
        val dao = db.scheduledMessageDao()
        val id = repo.allUnsettled().single().id
        dao.claimForSending(id, now = 10_000L)

        // Bail encore valide : rien ne bouge, et la ligne n'apparaît pas dans « Échecs ».
        assertThat(dao.markInterruptedIfStale(id, cutoff = 9_999L)).isEqualTo(0)
        assertThat(dao.observeFailed().first()).isEmpty()

        // Bail expiré : conclue, et enfin atteignable.
        assertThat(dao.markInterruptedIfStale(id, cutoff = 10_000L)).isEqualTo(1)
        assertThat(dao.observeFailed().first()).hasSize(1)
        // Et une seconde fois ne rejoue rien : la transition est terminale.
        assertThat(dao.markInterruptedIfStale(id, cutoff = 999_999L)).isEqualTo(0)
    }

    private suspend fun seed(conversationId: Long, inVault: Boolean, corps: String) {
        db.conversationDao().insert(
            ConversationEntity(
                id = conversationId,
                threadId = conversationId,
                addressesCsv = "+33600000001",
                displayName = null,
                lastMessageAt = 0L,
                lastMessagePreview = null,
                inVault = inVault,
            ),
        )
        db.scheduledMessageDao().upsert(
            ScheduledMessageEntity(
                conversationId = conversationId,
                addressesCsv = "+33600000001",
                body = corps,
                scheduledAt = System.currentTimeMillis() + 3_600_000L,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    private companion object {
        const val COFFRE = 1L
        const val ORDINAIRE = 2L
    }
}
