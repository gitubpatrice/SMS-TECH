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
 * v1.27.2 — verrouille la régression trouvée par la relecture Codex du 2026-08-04.
 *
 * `TelephonySyncManager.reconcileDeletions` efface les lignes dont le message a disparu côté
 * système. Ma première version appelait ensuite `refreshAllConversationPreviewsAfterPurge()`, qui
 * réécrit l'aperçu de **toutes** les conversations depuis `messages.body`.
 *
 * Le piège : pour un MMS **sans légende**, `body` est VIDE. Son libellé utile — « 🖼️ photo.jpg »,
 * un sujet — vit uniquement dans `conversations.last_message_preview`, posé à l'insertion par
 * `ConversationMirror.touchConversation`. Aucune colonne ne le porte par message, donc **aucune
 * requête ne peut le reconstruire**. Une suppression dans le fil B vidait ainsi l'aperçu du fil A,
 * parfaitement étranger à l'opération.
 *
 * Ces tests figent les deux moitiés du contrat : le recalcul **ciblé** ne touche que la
 * conversation concernée, et le recalcul **global** — encore utilisé par la purge d'historique —
 * a bien l'effet destructeur qui justifie de ne pas s'en servir ici.
 */
@RunWith(AndroidJUnit4::class)
class ReconcileDeletionsPreviewTest {

    private lateinit var db: AppDatabase

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val CONV_MMS = 1L // dernier message = MMS sans légende
        const val CONV_SMS = 2L // celle où la suppression a lieu
        const val MMS_LABEL = "Photo.jpg"
    }

    // ⚠️ Corps de BLOC, pas d'expression : JUnit4 exige des méthodes `void`, et un
    // `= runBlocking { … }` rend la valeur de sa dernière instruction.
    @Before
    fun setUp() { runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        db.conversationDao().upsert(conversation(CONV_MMS, "+33611111111", MMS_LABEL, at = 1_000L))
        db.conversationDao().upsert(conversation(CONV_SMS, "+33622222222", "Salut", at = 2_000L))

        // Le MMS sans légende : corps VIDE, une pièce jointe. C'est la ligne dont aucun recalcul
        // ne peut retrouver le libellé.
        db.messageDao().insert(
            message(
                id = 10L,
                convId = CONV_MMS,
                uri = "content://mms/10",
                body = "",
                attachments = 1,
                date = 1_000L,
            ),
        )
        // Deux SMS dans l'autre fil : l'un sera « supprimé côté système ».
        db.messageDao().insert(
            message(id = 20L, convId = CONV_SMS, uri = "content://sms/20", body = "Ancien", date = 1_500L),
        )
        db.messageDao().insert(
            message(id = 21L, convId = CONV_SMS, uri = "content://sms/21", body = "Salut", date = 2_000L),
        )
    } }

    @After
    fun tearDown() = db.close()

    private fun conversation(id: Long, address: String, preview: String, at: Long) =
        ConversationEntity(
            id = id,
            threadId = id,
            addressesCsv = address,
            displayName = null,
            lastMessageAt = at,
            lastMessagePreview = preview,
            unreadCount = 0,
        )

    private fun message(
        id: Long,
        convId: Long,
        uri: String,
        body: String,
        date: Long,
        attachments: Int = 0,
    ) = MessageEntity(
        id = id,
        conversationId = convId,
        telephonyUri = uri,
        address = "+3360000000$convId",
        body = body,
        type = if (attachments > 0) MessageType.MMS else MessageType.SMS,
        direction = MessageDirection.INCOMING,
        date = date,
        dateSent = null,
        read = true,
        starred = false,
        status = MessageStatus.DELIVERED,
        attachmentsCount = attachments,
    )

    private suspend fun previewOf(id: Long) = db.conversationDao().findById(id)?.lastMessagePreview

    // ──────────── La requête qui rend le recalcul ciblé possible ────────────

    @Test
    fun affectedConversationIds_areReadableBeforeTheDelete() { runBlocking {
        val ids = db.messageDao().findConversationIdsByTelephonyUris(listOf("content://sms/21"))

        // C'est tout l'enjeu de l'ordre des opérations : après le DELETE, la ligne n'existe plus
        // et cette requête ne rendrait rien.
        assertThat(ids).containsExactly(CONV_SMS)
    } }

    @Test
    fun affectedConversationIds_deduplicatesAcrossABatch() { runBlocking {
        val ids = db.messageDao()
            .findConversationIdsByTelephonyUris(listOf("content://sms/20", "content://sms/21"))

        assertThat(ids).containsExactly(CONV_SMS)
    } }

    // ──────────── Le comportement corrigé ────────────

    @Test
    fun targetedRefresh_leavesOtherConversationsUntouched() = runBlocking {
        val affected = db.messageDao()
            .findConversationIdsByTelephonyUris(listOf("content://sms/21"))
        db.messageDao().deleteByTelephonyUris(listOf("content://sms/21"))

        affected.forEach { db.messageDao().refreshConversationPreview(it) }

        // Le fil touché se recale sur son message restant.
        assertThat(previewOf(CONV_SMS)).isEqualTo("Ancien")
        // LE POINT DU TEST : le fil MMS n'a pas été effleuré, son libellé survit.
        assertThat(previewOf(CONV_MMS)).isEqualTo(MMS_LABEL)
    }

    // ──────────── Le comportement fautif, figé pour mémoire ────────────

    @Test
    fun globalRefresh_wipesTheCaptionlessMmsPreview() = runBlocking {
        db.messageDao().deleteByTelephonyUris(listOf("content://sms/21"))

        db.messageDao().refreshAllConversationPreviewsAfterPurge()

        assertThat(previewOf(CONV_SMS)).isEqualTo("Ancien")
        // Voilà ce que faisait ma première version : un fil sans rapport avec la suppression
        // perd son libellé, parce que le `body` du MMS est vide et qu'il est la seule source.
        //
        // L'aperçu devient la chaîne VIDE, pas `null` : la sous-requête sélectionne bien la ligne
        // MMS (sa pièce jointe la sauve du prédicat qui écarte les sentinelles) et rend son `body`
        // vide. C'est pourquoi le repli d'affichage de `ConversationRow` teste `isBlank()` et non
        // `== null` — il doit couvrir les deux.
        assertThat(previewOf(CONV_MMS)).isEmpty()
    }

    /**
     * ⚠️ Limite CONNUE et non corrigée, documentée ici pour qu'elle ne surprenne personne.
     *
     * Le recalcul ciblé souffre du même aveuglement dès qu'il porte **sur** une conversation dont
     * le dernier message restant est un MMS sans légende : la requête ne peut que lire `body`.
     * Le correctif du 2026-08-04 borne le rayon d'action du défaut, il ne supprime pas sa cause —
     * laquelle demanderait une colonne supplémentaire, donc une migration Room sur une base
     * chiffrée contenant tous les messages.
     */
    @Test
    fun targetedRefresh_stillLosesTheLabelOfTheConversationItTouches() = runBlocking {
        db.messageDao().refreshConversationPreview(CONV_MMS)

        assertThat(previewOf(CONV_MMS)).isEmpty()
    }
}
