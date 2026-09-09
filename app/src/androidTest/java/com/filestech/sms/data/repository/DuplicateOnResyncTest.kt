package com.filestech.sms.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.data.local.db.AppDatabase
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
 * v1.27.11 — **reproduit** le doublon, avant d'en corriger la cause.
 *
 * La mesure du 2026-09-07 a etabli que `resolver.insert(Telephony.Sms.Sent.CONTENT_URI, …)` rend
 * `content://sms/sent/<id>` sous Android 10, forme que `TelephonyReader` enregistre telle quelle
 * pour tout message que SMS Tech ecrit lui-meme. L'import, lui, construit
 * `content://sms/<id>` (`TelephonyReader`, ligne 379).
 *
 * L'index `UNIQUE(telephony_uri)` compare des CHAINES. Deux ecritures qui designent la MEME ligne
 * du systeme sous deux formes differentes ne se rencontrent donc jamais, et
 * `OnConflictStrategy.IGNORE` n'ignore rien. Une resynchronisation complete — le bouton
 * « Resynchroniser », qui remet le curseur a zero et rebalaie tout `content://sms` — reimporte
 * ainsi chaque message ecrit par l'application comme s'il etait nouveau.
 *
 * Ce test ne passe pas par le pipeline de synchronisation : il ecrit les deux formes directement,
 * ce qui isole le mecanisme. Ce qu'il affirme est exactement ce dont depend la correction — si un
 * jour l'index dedoublonnait ces deux formes, il echouerait, et la correction deviendrait inutile.
 */
@RunWith(AndroidJUnit4::class)
class DuplicateOnResyncTest {

    private lateinit var db: AppDatabase

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            db.conversationDao().insert(
                ConversationEntity(
                    id = 1L,
                    threadId = 1L,
                    addressesCsv = "+33612345678",
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
    fun lesDeuxFormesDuMemeMessageCoexistent_donnantUnDoublon() = runBlocking {
        // 1) Ce que l'application enregistre quand elle ecrit elle-meme le message.
        val ecritParLApp = row(telephonyUri = "content://sms/sent/9164")
        // 2) Ce que l'import construit pour LA MEME ligne systeme.
        val vuParLImport = row(telephonyUri = "content://sms/9164")

        val premier = db.messageDao().insert(ecritParLApp)
        val second = db.messageDao().insert(vuParLImport)

        // `IGNORE` rendrait -1 sur conflit. Les deux passent : l'index ne les rapproche pas.
        assertThat(premier).isGreaterThan(0L)
        assertThat(second).isGreaterThan(0L)

        val tous = db.messageDao().findByConversation(1L)
        assertThat(tous).hasSize(2)
        // Deux lignes, un seul message reel : c'est le doublon que voit l'utilisateur.
        assertThat(tous.map { it.body }.toSet()).hasSize(1)
    }

    @Test
    fun deuxFoisLaMemeForme_sontBienDedoublonnees() {
        // Controle positif : l'index fonctionne, le defaut est bien la DIVERGENCE de forme et
        // non une defaillance de la contrainte. Sans cette assertion, un index casse produirait
        // le meme resultat que le test precedent et on corrigerait la mauvaise cause.
        runBlocking {
            val premier = db.messageDao().insert(row(telephonyUri = "content://sms/9164"))
            val second = db.messageDao().insert(row(telephonyUri = "content://sms/9164"))

            assertThat(premier).isGreaterThan(0L)
            assertThat(second).isEqualTo(-1L)
            assertThat(db.messageDao().findByConversation(1L)).hasSize(1)
        }
    }

    private fun row(telephonyUri: String) = MessageEntity(
        conversationId = 1L,
        telephonyUri = telephonyUri,
        address = "+33612345678",
        body = "Message ecrit par SMS Tech",
        type = MessageType.SMS,
        direction = MessageDirection.OUTGOING,
        date = 1_700_000_000_000L,
        dateSent = 1_700_000_000_000L,
        read = true,
        starred = false,
        status = MessageStatus.SENT,
        errorCode = null,
        subId = null,
        scheduledAt = null,
        attachmentsCount = 0,
    )
}
