package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.MessageStatus
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

/**
 * v1.28.3 (groupes — **mesuré sur le S9**) — **un envoi fait depuis un groupe laisse une ligne
 * dans le fil du groupe.**
 *
 * Chaque ligne d'envoi vit dans la 1-à-1 de son destinataire ; le fil du groupe restait vide
 * après un envoi. L'écho est une copie locale, au statut de l'envoi dans son ensemble.
 */
class SendSmsGroupEchoTest {

    private class Reglages : AppSettingsSource {
        override val flow: Flow<AppSettings> = flowOf(AppSettings())
        override val state: StateFlow<AppSettings> = MutableStateFlow(AppSettings())
        override suspend fun hydratedOrNull(): AppSettings = AppSettings()
        override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
    }

    private val pat = PhoneAddress.of("0617332729")
    private val moi = PhoneAddress.of("0607231541")

    @Test
    fun `un envoi depuis un groupe ecrit une ligne par membre ET un echo dans le groupe`() = runBlocking {
        val mirror = NoopMirror()
        val issue = sendSmsUseCase(Reglages(), RecordingSender(), mirror = mirror)
            .invoke(listOf(pat, moi), "Test groupe", echoInGroup = true)

        assertThat(issue).isInstanceOf(Outcome.Success::class.java)
        assertThat(mirror.lignes.map { it.adresse }).containsExactly("0617332729", "0607231541")
        assertThat(mirror.echos).hasSize(1)
        assertThat(mirror.echos.single().adresses).containsExactly("0617332729", "0607231541").inOrder()
        assertThat(mirror.echos.single().corps).isEqualTo("Test groupe")
        assertThat(mirror.echos.single().statut).isEqualTo(MessageStatus.SENT)
    }

    /** Contrôle : un seul destinataire n'a pas de « groupe », et sans opt-in rien ne change. */
    @Test
    fun `pas d'echo pour un seul destinataire ni sans opt-in`() = runBlocking {
        val seul = NoopMirror()
        sendSmsUseCase(Reglages(), RecordingSender(), mirror = seul).invoke(listOf(pat), "x", echoInGroup = true)
        assertThat(seul.echos).isEmpty()

        val urgence = NoopMirror()
        sendSmsUseCase(Reglages(), RecordingSender(), mirror = urgence).invoke(listOf(pat, moi), "x")
        assertThat(urgence.echos).isEmpty()
        assertThat(urgence.lignes).hasSize(2)
    }

    /** Un membre bloqué : l'envoi n'a pas atteint tout le monde, l'écho le dit. */
    @Test
    fun `l'echo porte l'echec quand un membre n'a pas ete atteint`() = runBlocking {
        val mirror = NoopMirror()
        sendSmsUseCase(Reglages(), RecordingSender(), mirror = mirror, blocked = BlockedList(setOf("0607231541")))
            .invoke(listOf(pat, moi), "x", echoInGroup = true)

        assertThat(mirror.echos.single().statut).isEqualTo(MessageStatus.FAILED)
    }
}
