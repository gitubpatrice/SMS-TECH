package com.filestech.sms.data.backup

import android.content.Context
import android.net.Uri
import com.filestech.sms.core.crypto.AeadCipher
import com.filestech.sms.core.crypto.PasswordKdf
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.security.AppLockManager
import com.filestech.sms.security.VaultSecondFactorPolicy
import com.filestech.sms.security.VaultSessionState
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * v1.28.3 (audit global A-03) — **la restauration refuse en session leurre, comme l'export.**
 *
 * `writeSmsbk` portait la garde depuis l'audit C2 ; `readSmsbk`, son jumeau, non. Aucun chemin
 * n'y menait aujourd'hui — l'entrée est masquée en leurre et la pile est vidée au verrouillage —
 * mais la règle de ce dépôt est que le service refuse, pas seulement que l'écran masque.
 *
 * Les collaborateurs sont des doublures STRICTES, volontairement : la garde est posée avant
 * qu'aucun d'eux ne soit sollicité, et c'est précisément ce que le premier test mesure — une
 * doublure sollicitée lèverait. (Une première version « relaxed » rendait, au contrôle positif,
 * un `InputStream` factice qui lisait zéro octet sans fin, jusqu'à l'`OutOfMemoryError` de
 * l'exécuteur de tests : le repli permissif d'une doublure est aussi un repli.)
 */
class RestoreDecoyGuardTest {

    private val appLock = mockk<AppLockManager>()

    private fun service() = BackupService(
        context = mockk<Context>(),
        database = mockk<AppDatabase>(),
        conversationDao = mockk<ConversationDao>(),
        messageDao = mockk<MessageDao>(),
        kdf = mockk<PasswordKdf>(),
        aead = mockk<AeadCipher>(),
        appLock = appLock,
        vaultSession = mockk<VaultSessionState>(),
        vaultFactor = mockk<VaultSecondFactorPolicy>(),
        io = Dispatchers.Unconfined,
    )

    private fun etat(state: AppLockManager.LockState) {
        every { appLock.state } returns MutableStateFlow(state)
    }

    @Test
    fun `en session leurre la restauration est refusee et la passphrase effacee`() = runTest {
        etat(AppLockManager.LockState.PanicDecoy)
        val passphrase = "secret".toCharArray()

        val issue = service().readSmsbk(mockk<Uri>(), passphrase)

        assertThat(issue).isEqualTo(Outcome.Failure(AppError.Locked()))
        assertThat(passphrase.all { it == '\u0000' }).isTrue()
    }

    /**
     * Contrôle POSITIF : hors leurre, la même requête n'est PAS refusée par le verrou. Elle
     * échoue plus loin — la doublure stricte de `Context` lève dès `contentResolver` — et c'est
     * cet échec-là, et pas `Locked`, qu'on attend.
     */
    @Test
    fun `hors leurre le verrou ne refuse pas`() = runTest {
        etat(AppLockManager.LockState.Unlocked)

        val issue = service().readSmsbk(mockk<Uri>(), "secret".toCharArray())

        assertThat(issue).isInstanceOf(Outcome.Failure::class.java)
        assertThat((issue as Outcome.Failure).error).isNotInstanceOf(AppError.Locked::class.java)
    }
}
