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
