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

    private fun routeur(groupMms: Boolean, bloques: Set<String> = emptySet()): EnvoyerMessageUseCase {
        val reglages = Reglages(groupMms)
        val liste = if (bloques.isEmpty()) NeverBlocked() else BlockedList(bloques)
        return EnvoyerMessageUseCase(
            sendSms = sendSmsUseCase(settings = reglages, sender = sms, mirror = miroir),
            sendMediaMms = SendMediaMmsUseCase(
                defaultAppManager = alwaysDefaultSmsApp(),
                blockedRepo = liste,
                mirror = miroir,
                sender = mms,
                settings = reglages,
                attachmentStore = PassthroughAttachmentStore(),
                simDEnvoi = simDEnvoiNeutre(),
            ),
            settings = reglages,
            blockedRepo = liste,
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

    // ── 2026-11-14 — le groupe de 56 du S24 de Patrice : un membre bloqué, un message parti ailleurs ──────────

    private val trois = groupe + PhoneAddress.of("+33600000003")

    @Test
    fun `MMS de groupe - les membres bloques sont annonces avant l'envoi`() = runBlocking<Unit> {
        val ecartes = routeur(groupMms = true, bloques = setOf("+33600000003")).membresEcartesDuGroupe(trois)

        assertThat(ecartes.map { it.raw }).containsExactly("+33600000003")
    }

    /** Contrôle négatif : en SMS par destinataire, rien ne change de conversation — rien à annoncer ici. */
    @Test
    fun `reglage inactif ou destinataire seul - rien a annoncer`() = runBlocking {
        val bloque = setOf("+33600000001")

        assertThat(routeur(groupMms = false, bloques = bloque).membresEcartesDuGroupe(trois)).isEmpty()
        assertThat(routeur(groupMms = true, bloques = bloque).membresEcartesDuGroupe(seul)).isEmpty()
        assertThat(routeur(groupMms = true).membresEcartesDuGroupe(trois)).isEmpty()
    }

    /** « Envoyé à 1 destinataire(s) » pour un MMS parti à 55 personnes : le rapport compte désormais les personnes. */
    @Test
    fun `MMS de groupe - le rapport compte les personnes servies, pas le MMS`() = runBlocking<Unit> {
        val res = routeur(groupMms = true, bloques = setOf("+33600000003")).invoke(trois, "salut")

        val rapport = (res as Outcome.Success).value
        assertThat(rapport.dispatched).hasSize(1)
        assertThat(rapport.servis).isEqualTo(2)
        assertThat(rapport.blocked.map { it.raw }).containsExactly("+33600000003")
        assertThat(mms.destinatairesTentes).containsExactly("+33600000001", "+33600000002")
    }

    /** Contrôle : un message par destinataire, autant de personnes que de messages. */
    @Test
    fun `SMS par destinataire - servis vaut le nombre de messages`() = runBlocking {
        val res = routeur(groupMms = false).invoke(trois, "salut")

        assertThat((res as Outcome.Success).value.servis).isEqualTo(3)
    }
}
