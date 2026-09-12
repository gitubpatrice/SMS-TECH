package com.filestech.sms.security

import android.content.Context
import com.filestech.sms.core.crypto.KeystoreManager
import com.filestech.sms.data.local.datastore.SecurityStore
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.DatabaseKeyManager
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.repository.ConversationEraser
import com.filestech.sms.domain.security.PanicStateProvider
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.settings.LockMode
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * v1.28.6 — « Supprimer toutes mes données » en session leurre n'efface que ce que le leurre montre.
 *
 * Trouvé par l'audit de motif du 2026-09-12 : le bouton, volontairement visible en leurre (v1.27.11,
 * comme « Réinitialiser tous les réglages »), exécutait la purge TOTALE — coffre réel, PIN, code
 * panique — depuis une session dont la raison d'être est de les préserver. Aucun test n'existait
 * sur `nukeEverything`, dans aucun module.
 *
 * Les deux chemins sont tenus : en leurre, l'effaceur reçoit chaque conversation hors coffre en
 * mode ordinaire et RIEN d'autre n'est touché ; hors leurre, la purge totale reste ce qu'elle
 * était. Contrôle négatif fait le 2026-09-12 : sans la branche, les deux tests « en leurre » tombent
 * (l'effaceur n'est jamais appelé).
 */
class PanicServiceDecoyTest {

    @TempDir lateinit var dossier: File

    private class FakeSettings(initial: AppSettings) : AppSettingsSource {
        private val backing = MutableStateFlow(initial)
        override val flow: Flow<AppSettings> get() = backing
        override val state: StateFlow<AppSettings> get() = backing
        override suspend fun hydratedOrNull(): AppSettings = backing.value
        override suspend fun update(transform: (AppSettings) -> AppSettings) = backing.update(transform)
    }

    private val securiteArmee = AppSettings().security.copy(lockMode = LockMode.PIN, flagSecure = false)
    private val reglagesModifies = AppSettings(security = securiteArmee).let {
        it.copy(advanced = it.advanced.copy(splashShown = true))
    }

    private val database = mockk<AppDatabase>(relaxed = true)
    private val keyManager = mockk<DatabaseKeyManager>(relaxed = true)
    private val keystore = mockk<KeystoreManager>(relaxed = true)
    private val securityStore = mockk<SecurityStore>(relaxed = true)
    private val conversationDao = mockk<ConversationDao>()
    private val eraser = mockk<ConversationEraser>()
    private val barriere = VaultPurgeBarrier()
    private val settings = FakeSettings(reglagesModifies)

    private fun fichier(chemin: String): File = File(dossier, chemin).apply {
        parentFile!!.mkdirs()
        writeText("x")
    }

    private fun service(decoy: Boolean): PanicService {
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns File(dossier, "files").apply { mkdirs() }
        every { context.cacheDir } returns File(dossier, "cache").apply { mkdirs() }
        every { context.getDatabasePath(any()) } returns File(dossier, "databases/${AppDatabase.DATABASE_NAME}")
        val panicState = object : PanicStateProvider {
            override val isPanicDecoyActive: Boolean = decoy
        }
        return PanicService(
            context = context,
            database = database,
            keyManager = keyManager,
            keystore = keystore,
            securityStore = securityStore,
            settings = settings,
            panicState = panicState,
            conversationDao = conversationDao,
            eraser = eraser,
            barriere = barriere,
            io = UnconfinedTestDispatcher(),
        )
    }

    @Test
    fun `en leurre, seules les conversations hors coffre partent, par l'effaceur ordinaire`() = runTest {
        coEvery { conversationDao.idsHorsCoffre() } returns listOf(7L, 9L)
        coEvery { eraser.erase(any(), any()) } returns ConversationEraser.Issue(true, true)
        val pieceJointeDuCoffre = fichier("files/mms_attachments/coffre.jpg")
        val export = fichier("files/exports/fil.pdf")

        service(decoy = true).nukeEverything()

        coVerify(exactly = 1) { eraser.erase(7L, ConversationEraser.Mode.ORDINAIRE) }
        coVerify(exactly = 1) { eraser.erase(9L, ConversationEraser.Mode.ORDINAIRE) }
        coVerify(exactly = 0) { eraser.erase(any(), ConversationEraser.Mode.COFFRE) }
        coVerify(exactly = 0) { eraser.erase(any(), ConversationEraser.Mode.COFFRE_FORCE) }
        // Rien de ce que le leurre protège n'est touché.
        verify(exactly = 0) { database.close() }
        verify(exactly = 0) { keyManager.destroyKeyFile() }
        verify(exactly = 0) { keystore.deleteKey(any()) }
        coVerify(exactly = 0) { securityStore.clearPin() }
        coVerify(exactly = 0) { securityStore.clearPanic() }
        coVerify(exactly = 0) { securityStore.setFailCount(any()) }
        coVerify(exactly = 0) { securityStore.clearLockout() }
        assertThat(pieceJointeDuCoffre.exists()).isTrue()
        // Le bloc sécurité est préservé, le reste revient aux défauts, le splash se rejouera.
        assertThat(settings.state.value.security).isEqualTo(securiteArmee)
        assertThat(settings.state.value.advanced.splashShown).isFalse()
        assertThat(export.exists()).isFalse()
    }

    @Test
    fun `en leurre, une conversation qui resiste n'empeche pas les suivantes ni les reglages`() = runTest {
        coEvery { conversationDao.idsHorsCoffre() } returns listOf(1L, 2L, 3L)
        coEvery { eraser.erase(2L, any()) } throws IllegalStateException("verrou")
        coEvery { eraser.erase(1L, any()) } returns ConversationEraser.Issue(true, true)
        coEvery { eraser.erase(3L, any()) } returns ConversationEraser.Issue(true, true)

        service(decoy = true).nukeEverything()

        coVerify(exactly = 1) { eraser.erase(3L, ConversationEraser.Mode.ORDINAIRE) }
        assertThat(settings.state.value.advanced.splashShown).isFalse()
        assertThat(settings.state.value.security).isEqualTo(securiteArmee)
    }

    /**
     * v1.28.6 — UNE COPIE SYSTEME QUI RESISTE EST COMPTEE, pour que l'application le DISE au lieu
     * de promettre « irréversible ». Cause habituelle : SMS Tech n'est pas l'application SMS par
     * défaut et le système lui refuse la suppression. Un échec dont on ne sait rien compte ici
     * aussi : le doute se résout du côté « nous n'avons pas tout effacé ».
     */
    @Test
    fun `une copie systeme qui resiste, ou un echec, est comptee en residu`() = runTest {
        coEvery { conversationDao.idsToutes() } returns listOf(1L, 2L, 3L)
        coEvery { eraser.erase(1L, any()) } returns ConversationEraser.Issue(systemCopyGone = true, localeComplete = true)
        coEvery { eraser.erase(2L, any()) } returns ConversationEraser.Issue(systemCopyGone = false, localeComplete = true)
        coEvery { eraser.erase(3L, any()) } throws IllegalStateException("fournisseur indisponible")

        val residu = service(decoy = false).nukeEverything()

        assertThat(residu.copiesSystemeRestantes).isEqualTo(2)
        // Et la destruction a bien eu lieu malgre les residus : la base ne survit pas a la purge.
        verify(exactly = 1) { database.close() }
        coVerify(exactly = 1) { securityStore.clearPin() }
    }

    @Test
    fun `hors leurre, TOUTES les conversations passent par l'effaceur puis tout est detruit`() = runTest {
        coEvery { conversationDao.idsToutes() } returns listOf(7L, 8L)
        coEvery { eraser.erase(any(), any()) } returns ConversationEraser.Issue(true, true)

        val residu = service(decoy = false).nukeEverything()

        // v1.28.6 — les copies dans `content://sms` partent AVANT la base : sans cela, la
        // resynchronisation du lancement suivant ramenait tout, coffre compris et en clair.
        coVerify(exactly = 1) { eraser.erase(7L, ConversationEraser.Mode.ORDINAIRE) }
        coVerify(exactly = 1) { eraser.erase(8L, ConversationEraser.Mode.ORDINAIRE) }
        coVerify(exactly = 0) { conversationDao.idsHorsCoffre() }
        assertThat(residu.copiesSystemeRestantes).isEqualTo(0)
        verify(exactly = 1) { database.close() }
        verify(exactly = 1) { keyManager.destroyKeyFile() }
        verify(exactly = 1) { keystore.deleteKey(KeystoreManager.ALIAS_DB_MASTER) }
        verify(exactly = 1) { keystore.deleteKey(KeystoreManager.ALIAS_VAULT_KEK) }
        verify(exactly = 1) { keystore.deleteKey(KeystoreManager.ALIAS_SETTINGS_AEAD) }
        verify(exactly = 1) { keystore.deleteKey(KeystoreManager.ALIAS_PANIC_DECOY) }
        coVerify(exactly = 1) { securityStore.clearPin() }
        coVerify(exactly = 1) { securityStore.clearPanic() }
        coVerify(exactly = 1) { securityStore.setFailCount(0) }
        coVerify(exactly = 1) { securityStore.clearLockout() }
        assertThat(settings.state.value).isEqualTo(AppSettings())
    }
}
