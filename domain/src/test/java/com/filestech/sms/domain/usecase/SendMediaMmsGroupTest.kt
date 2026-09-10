package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.PhoneAddress
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * v1.28.4 — **le MMS de groupe : un seul PDU à tous, une seule ligne, dans le groupe.**
 *
 * Réglage désactivé, rien ne change : un message par destinataire (contrôle positif). Activé,
 * le radio est sollicité UNE fois avec tous les destinataires, et le miroir n'écrit qu'une
 * ligne, adressée au groupe. Un membre bloqué sort du PDU sans faire tomber les autres ; tous
 * bloqués, c'est un refus de blocage, pas une panne de téléphonie.
 */
class SendMediaMmsGroupTest {

    private companion object {
        const val ALICE = "+33611111111"
        const val BOB = "+33622222222"
    }

    private fun fichier(dir: File): File = File(dir, "photo.jpg").apply { writeBytes(ByteArray(64) { 1 }) }

    private fun useCase(mirror: NoopMirror, dispatcher: RecordingMmsDispatcher, bloques: Set<String> = emptySet()) =
        SendMediaMmsUseCase(
            defaultAppManager = alwaysDefaultSmsApp(),
            blockedRepo = BlockedList(bloques),
            mirror = mirror,
            sender = dispatcher,
            settings = DefaultSettingsSource(),
            attachmentStore = PassthroughAttachmentStore(),
        )

    private fun photo(dir: File) = listOf(SendMediaMmsUseCase.AttachmentPayload(fichier(dir), "image/jpeg"))

    @Test
    fun `active, un seul PDU a tous et une seule ligne dans le groupe`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()

        val out = runBlocking {
            useCase(mirror, dispatcher).invoke(
                recipients = listOf(PhoneAddress.of(ALICE), PhoneAddress.of(BOB)),
                attachments = photo(dir),
                textBody = "regarde",
                groupMms = true,
            )
        }

        val rapport = (out as Outcome.Success).value
        assertThat(rapport.dispatched).hasSize(1)
        assertThat(rapport.isComplete).isTrue()
        assertThat(dispatcher.appels).isEqualTo(1)
        assertThat(dispatcher.destinatairesTentes).containsExactly(ALICE, BOB)
        assertThat(mirror.lignes).hasSize(1)
        assertThat(mirror.lignes.single().adresse).isEqualTo("$ALICE;$BOB")
    }

    /** Contrôle POSITIF : réglage désactivé, le comportement d'avant — un message par destinataire. */
    @Test
    fun `desactive, un message par destinataire comme avant`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()

        runBlocking {
            useCase(mirror, dispatcher).invoke(
                recipients = listOf(PhoneAddress.of(ALICE), PhoneAddress.of(BOB)),
                attachments = photo(dir),
                groupMms = false,
            )
        }

        assertThat(dispatcher.appels).isEqualTo(2)
        assertThat(mirror.lignes.map { it.adresse }).containsExactly(ALICE, BOB)
    }

    /** Un texte seul part aussi en MMS de groupe : c'est ce qui fait que tout le monde voit tout. */
    @Test
    fun `active, un texte seul part en MMS de groupe`() {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()

        val out = runBlocking {
            useCase(mirror, dispatcher).invoke(
                recipients = listOf(PhoneAddress.of(ALICE), PhoneAddress.of(BOB)),
                attachments = emptyList(),
                textBody = "on se voit demain ?",
                groupMms = true,
            )
        }

        assertThat(out).isInstanceOf(Outcome.Success::class.java)
        assertThat(dispatcher.appels).isEqualTo(1)
        assertThat(dispatcher.dernierTexte).isEqualTo("on se voit demain ?")
    }

    @Test
    fun `un membre bloque sort du PDU sans faire tomber les autres`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()

        val out = runBlocking {
            useCase(mirror, dispatcher, bloques = setOf(BOB)).invoke(
                recipients = listOf(PhoneAddress.of(ALICE), PhoneAddress.of(BOB)),
                attachments = photo(dir),
                groupMms = true,
            )
        }

        val rapport = (out as Outcome.Success).value
        assertThat(dispatcher.destinatairesTentes).containsExactly(ALICE)
        assertThat(rapport.blocked.map { it.raw }).containsExactly(BOB)
        assertThat(rapport.isComplete).isFalse()
    }

    @Test
    fun `tous bloques, c'est un refus de blocage et rien ne part`(@TempDir dir: File) {
        val dispatcher = RecordingMmsDispatcher()

        val out = runBlocking {
            useCase(NoopMirror(), dispatcher, bloques = setOf(ALICE, BOB)).invoke(
                recipients = listOf(PhoneAddress.of(ALICE), PhoneAddress.of(BOB)),
                attachments = photo(dir),
                groupMms = true,
            )
        }

        assertThat(out).isEqualTo(Outcome.Failure(AppError.RecipientBlocked))
        assertThat(dispatcher.appels).isEqualTo(0)
    }

    /** Un échec du radio marque la ligne unique en échec et le dit — pas de ligne « envoyée » qui ment. */
    @Test
    fun `un echec du radio marque la ligne du groupe en echec`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher(outcome = Outcome.Failure(AppError.Telephony("radio")))

        val out = runBlocking {
            useCase(mirror, dispatcher).invoke(
                recipients = listOf(PhoneAddress.of(ALICE), PhoneAddress.of(BOB)),
                attachments = photo(dir),
                groupMms = true,
            )
        }

        assertThat(out).isInstanceOf(Outcome.Failure::class.java)
        assertThat(mirror.statuts.map { it.second }).containsExactly(MessageStatus.FAILED)
    }
}
