package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * v1.28.4 — **la règle d'aiguillage, à un seul endroit, testée à un seul endroit.** Le fil et
 * l'envoi programmé l'écrivaient chacun ; l'envoi programmé avait divergé. Le routeur lit le
 * réglage lui-même : un appelant ne peut plus l'oublier.
 */
class EnvoyerMessageUseCaseTest {

    private class Reglages(groupMms: Boolean) : AppSettingsSource {
        private val value = AppSettings().let { it.copy(sending = it.sending.copy(groupMms = groupMms)) }
        override val flow: Flow<AppSettings> = flowOf(value)
        override val state: StateFlow<AppSettings> = MutableStateFlow(value)
        override suspend fun hydratedOrNull(): AppSettings = value
        override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
    }

    private val sms = RecordingSender()
    private val mms = RecordingMmsDispatcher()
    private val miroir = NoopMirror()

    private fun routeur(groupMms: Boolean): EnvoyerMessageUseCase {
        val reglages = Reglages(groupMms)
        return EnvoyerMessageUseCase(
            sendSms = sendSmsUseCase(settings = reglages, sender = sms, mirror = miroir),
            sendMediaMms = SendMediaMmsUseCase(
                defaultAppManager = alwaysDefaultSmsApp(),
                blockedRepo = NeverBlocked(),
                mirror = miroir,
                sender = mms,
                settings = reglages,
                attachmentStore = PassthroughAttachmentStore(),
            ),
            settings = reglages,
        )
    }

    private val groupe = listOf(PhoneAddress.of("+33600000001"), PhoneAddress.of("+33600000002"))
    private val seul = listOf(PhoneAddress.of("+33600000001"))

    private fun photo(dir: File) = listOf(
        SendMediaMmsUseCase.AttachmentPayload(
            file = File(dir, "p.jpg").apply { writeBytes(ByteArray(64) { 1 }) },
            mimeType = "image/jpeg",
        ),
    )

    @Test
    fun `un texte vers un groupe, reglage actif, part en UN MMS a tous`() = runBlocking {
        val res = routeur(groupMms = true).invoke(groupe, "salut")

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        assertThat(mms.appels).isEqualTo(1)
        assertThat(mms.destinatairesTentes).containsExactly("+33600000001", "+33600000002")
        assertThat(sms.sentTexts).isEmpty()
    }

    /** Contrôle : réglage inactif, le même texte part en SMS par destinataire. */
    @Test
    fun `un texte vers un groupe, reglage inactif, part en SMS par destinataire`() = runBlocking {
        val res = routeur(groupMms = false).invoke(groupe, "salut")

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        assertThat(sms.sentTexts).hasSize(2)
        assertThat(mms.appels).isEqualTo(0)
    }

    @Test
    fun `une piece jointe part en MMS, meme reglage inactif et meme pour un seul destinataire`(
        @TempDir dir: File,
    ) = runBlocking {
        val res = routeur(groupMms = false).invoke(seul, "", attachments = photo(dir))

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        assertThat(mms.appels).isEqualTo(1)
        assertThat(sms.sentTexts).isEmpty()
    }

    /** Contrôle : le réglage ne concerne que les groupes — un seul destinataire reste en SMS. */
    @Test
    fun `un texte vers un seul destinataire reste un SMS, reglage actif compris`() = runBlocking {
        routeur(groupMms = true).invoke(seul, "salut")

        assertThat(sms.sentTexts).hasSize(1)
        assertThat(mms.appels).isEqualTo(0)
    }
}
