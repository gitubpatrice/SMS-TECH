package com.filestech.sms.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
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
 * v1.28.3 (F01) — **reproduit la perte de conversation**, puis prouve qu'elle ne peut plus
 * se produire.
 *
 * # Le défaut
 *
 * `conversations.thread_id` portait un index UNIQUE et était `NOT NULL`. Les deux chemins qui
 * créent une conversation sans fil système — la composition (`findOrCreate`) et la réception
 * (`ConversationMirror.ensureConversation`) — y écrivaient tous deux la sentinelle `0L`. La
 * seconde conversation locale entrait donc en conflit sur l'index, et le DAO insérait avec
 * `OnConflictStrategy.REPLACE`, qui en SQLite **supprime** la ligne en conflit avant d'insérer
 * la nouvelle. La première conversation disparaissait, et ses messages puis ses pièces jointes
 * suivaient par `ForeignKey.CASCADE`.
 *
 * La relecture externe (MR F-Droid !38458) l'a reproduit sur émulateur avec deux brouillons
 * créés d'affilée : seul le second survivait. Sur ce chemin la perte est définitive — un
 * brouillon n'existe nulle part côté fournisseur système.
 *
 * # Ce que ces tests verrouillent
 *
 * Les deux moitiés du correctif, séparément :
 *
 *  - [plusieursConversationsSansFilSysteme_coexistent] tient la colonne nullable. Il tombe si
 *    quelqu'un rétablit `NOT NULL` ou réintroduit une sentinelle partagée (`0L`, ou les
 *    placeholders négatifs que `BackupService` fabriquait).
 *  - [collisionSurUnFilSystemeReel_leve_sansRienDetruire] tient la stratégie de conflit. Il
 *    tombe si quelqu'un remet `REPLACE` sur `ConversationDao.insert` : la ligne existante et
 *    ses messages seraient alors effacés au lieu que l'erreur remonte.
 *
 * Le second est le contrôle négatif du premier : la nullabilité seule ne protège pas une
 * conversation qui, elle, porte un vrai `thread_id`.
 */
@RunWith(AndroidJUnit4::class)
class ConversationIdentityTest {

    private lateinit var db: AppDatabase

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    /**
     * Le scénario exact de la relecture externe : deux conversations créées d'affilée, aucune
     * n'ayant encore de fil système, chacune portant sa propre donnée.
     */
    @Test
    fun plusieursConversationsSansFilSysteme_coexistent(): Unit = runBlocking {
        val premiere = db.conversationDao().insert(conv(addressesCsv = ADRESSE_A, draft = "auditA"))
        db.messageDao().insert(message(premiere, "message de la premiere"))

        val seconde = db.conversationDao().insert(conv(addressesCsv = ADRESSE_B, draft = "auditB"))
        val troisieme = db.conversationDao().insert(conv(addressesCsv = ADRESSE_C, draft = "auditC"))

        // Trois lignes distinctes, là où la sentinelle partagée n'en laissait qu'une.
        val toutes = db.conversationDao().listAllIncludingArchived()
        assertThat(toutes.map { it.id }).containsExactly(premiere, seconde, troisieme)
        assertThat(toutes.map { it.draft }).containsExactly("auditA", "auditB", "auditC")
        assertThat(toutes.map { it.threadId }).containsExactly(null, null, null)

        // Et surtout : la première n'a pas été vidée par cascade.
        assertThat(db.messageDao().findByConversation(premiere).map { it.body })
            .containsExactly("message de la premiere")
    }

    /**
     * Contrôle négatif. Deux conversations ne peuvent pas partager un `thread_id` RÉEL — c'est
     * l'invariant que l'index UNIQUE existe pour tenir. Ce qui change avec le correctif n'est
     * pas qu'on l'accepte, c'est la manière de le refuser : l'insertion échoue et la donnée
     * existante reste intacte, au lieu d'être silencieusement remplacée.
     */
    @Test
    fun collisionSurUnFilSystemeReel_leve_sansRienDetruire(): Unit = runBlocking {
        val existante = db.conversationDao().insert(
            conv(addressesCsv = ADRESSE_A, threadId = FIL_SYSTEME, draft = "a garder"),
        )
        db.messageDao().insert(message(existante, "a garder aussi"))

        val issue = runCatching {
            db.conversationDao().insert(
                conv(addressesCsv = ADRESSE_B, threadId = FIL_SYSTEME, draft = "intrus"),
            )
        }

        // 1. L'erreur remonte au lieu d'être avalée.
        assertThat(issue.isFailure).isTrue()

        // 2. Rien n'a été détruit — c'est le cœur de F01. Avec `REPLACE`, cette conversation
        //    et son message auraient disparu au profit de l'intrus.
        val toutes = db.conversationDao().listAllIncludingArchived()
        assertThat(toutes.map { it.id }).containsExactly(existante)
        assertThat(toutes.single().draft).isEqualTo("a garder")
        assertThat(db.messageDao().findByConversation(existante).map { it.body })
            .containsExactly("a garder aussi")
    }

    private fun conv(
        addressesCsv: String,
        threadId: Long? = null,
        draft: String? = null,
    ) = ConversationEntity(
        threadId = threadId,
        addressesCsv = addressesCsv,
        displayName = null,
        lastMessageAt = 0L,
        lastMessagePreview = null,
        draft = draft,
    )

    private fun message(conversationId: Long, body: String) = MessageEntity(
        conversationId = conversationId,
        telephonyUri = null,
        address = ADRESSE_A,
        body = body,
        type = MessageType.SMS,
        direction = MessageDirection.INCOMING,
        date = 1_700_000_000_000L,
        dateSent = null,
        read = false,
        starred = false,
        status = MessageStatus.SENT,
        errorCode = null,
        subId = null,
        scheduledAt = null,
        attachmentsCount = 0,
    )

    private companion object {
        const val ADRESSE_A = "+33600000001"
        const val ADRESSE_B = "+33600000002"
        const val ADRESSE_C = "+33600000003"
        const val FIL_SYSTEME = 42L
    }
}
