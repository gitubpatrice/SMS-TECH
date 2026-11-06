package com.filestech.sms.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 2026-11-06 — `ConversationDao.observeAllNonVault`, la lecture unique de la liste des conversations.
 *
 * La liste répartit cette lecture entre ses trois vues (principale, Archives, bloquées). Elle doit donc
 * rendre les archivées AVEC les autres — sans elles, une conversation bloquée et archivée disparaîtrait du
 * dossier — et jamais une conversation du coffre, qui apparaîtrait alors en clair dans la liste.
 * [leCoffreNEstJamaisLu] est le contrôle négatif de [archiveesEtNonArchiveesDansLOrdreDeLaListe].
 */
@RunWith(AndroidJUnit4::class)
class ConversationsHorsCoffreDaoTest {

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

    @Test
    fun archiveesEtNonArchiveesDansLOrdreDeLaListe(): Unit = runBlocking {
        val dao = db.conversationDao()
        val ancienne = dao.insert(conv("+33600000001", lastMessageAt = 100L))
        val archivee = dao.insert(conv("+33600000002", lastMessageAt = 300L, archived = true))
        val epinglee = dao.insert(conv("+33600000003", lastMessageAt = 50L, pinned = true))
        val recente = dao.insert(conv("+33600000004", lastMessageAt = 200L))

        val lues = dao.observeAllNonVault().first()

        // Même tri que les deux lectures d'avant : épinglées d'abord, puis la plus récente.
        assertThat(lues.map { it.id }).containsExactly(epinglee, archivee, recente, ancienne).inOrder()
    }

    @Test
    fun leCoffreNEstJamaisLu(): Unit = runBlocking {
        val dao = db.conversationDao()
        val visible = dao.insert(conv("+33600000001", lastMessageAt = 100L))
        dao.insert(conv("+33600000002", lastMessageAt = 200L, inVault = true))
        dao.insert(conv("+33600000003", lastMessageAt = 300L, inVault = true, archived = true))

        assertThat(dao.observeAllNonVault().first().map { it.id }).containsExactly(visible)
    }

    private fun conv(
        address: String,
        lastMessageAt: Long,
        archived: Boolean = false,
        pinned: Boolean = false,
        inVault: Boolean = false,
    ) = ConversationEntity(
        threadId = null,
        addressesCsv = address,
        displayName = null,
        lastMessageAt = lastMessageAt,
        lastMessagePreview = null,
        pinned = pinned,
        archived = archived,
        inVault = inVault,
    )
}
