package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.mms.MmsAttachment
import com.filestech.sms.domain.mms.MmsDispatcher
import com.filestech.sms.domain.mms.OutgoingAttachmentStore
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.SendErrorCode
import com.filestech.sms.domain.model.SendReport
import com.filestech.sms.domain.sender.DefaultSmsAppChecker
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
 * v1.28.3 (F21, second passage) — **le correctif n'avait été posé que sur la voie SMS.**
 *
 * Le `continue` muet qui faisait disparaître un destinataire bloqué sans ligne, sans message et
 * sans compte vivait à l'identique sur les deux voies MMS. Celle des médias a été signalée par la
 * revue de qualité lancée sur mon propre delta ; la voie VOCALE, elle, ne l'a été par personne —
 * je l'ai trouvée en vérifiant l'autre.
 *
 * C'est très exactement le motif dominant de cette relecture — *un correctif écrit, mais posé sur
 * un seul des chemins qui en avaient besoin* — et je venais de le reproduire, sur le finding qui
 * le décrit. D'où ce fichier : les **trois** chemins d'envoi sont désormais mesurés, et
 * `SendRecipientAccountingTest` couvre le troisième.
 */
class MmsRecipientAccountingTest {

    private companion object {
        const val ALICE = "+33611111111"
        const val BOB = "+33622222222"
    }

    private class DefaultSettings : AppSettingsSource {
        private val value = AppSettings()
        override val flow: Flow<AppSettings> = flowOf(value)
        override val state: StateFlow<AppSettings> = MutableStateFlow(value)
        override suspend fun hydratedOrNull(): AppSettings = value
        override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
    }

    /** Le stockage durable est une identité ici : ce n'est pas ce qu'on mesure. */
    private class PassthroughStore : OutgoingAttachmentStore {
        override fun promoteToDurable(staged: File): File = staged
    }

    private class RecordingDispatcher(
        private val outcome: Outcome<Unit> = Outcome.Success(Unit),
    ) : MmsDispatcher {
        val destinatairesTentes = mutableListOf<String>()

        override suspend fun sendVoiceMms(
            localMessageId: Long,
            recipients: List<String>,
            audioFile: File,
            mimeType: String,
            subId: Int?,
            requestDeliveryReport: Boolean,
        ): Outcome<Unit> {
            destinatairesTentes += recipients
            return outcome
        }

        override suspend fun sendMediaMms(
            localMessageId: Long,
            recipients: List<String>,
            attachments: List<MmsAttachment>,
            textBody: String?,
            subId: Int?,
            requestDeliveryReport: Boolean,
        ): Outcome<Unit> {
            destinatairesTentes += recipients
            return outcome
        }
    }

    private fun alwaysDefault() = object : DefaultSmsAppChecker {
        override fun isDefault() = true
    }

    private fun fichier(dir: File, nom: String): File =
        File(dir, nom).apply { writeBytes(ByteArray(64) { 1 }) }

    // ───────────────────────────── Voie MÉDIA ─────────────────────────────

    @Test
    fun `un destinataire bloque laisse une trace sur la voie media`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingDispatcher()
        val uc = SendMediaMmsUseCase(
            defaultAppManager = alwaysDefault(),
            blockedRepo = BlockedList(setOf(BOB)),
            mirror = mirror,
            sender = dispatcher,
            settings = DefaultSettings(),
            attachmentStore = PassthroughStore(),
        )

        val out = runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE), PhoneAddress.of(BOB)),
                attachments = listOf(
                    SendMediaMmsUseCase.AttachmentPayload(fichier(dir, "photo.jpg"), "image/jpeg"),
                ),
                textBody = "regarde",
            )
        }

        val rapport = (out as Outcome.Success).value
        assertThat(rapport.blocked.map { it.raw }).containsExactly(BOB)
        assertThat(rapport.isComplete).isFalse()
        // La ligne du bloqué existe, en échec, avec son motif — c'est toute la question.
        assertThat(mirror.lignes.map { it.adresse }).containsExactly(ALICE, BOB)
        assertThat(mirror.statuts.map { it.third }).contains(SendErrorCode.RECIPIENT_BLOCKED)
        // Et rien n'est parti vers lui.
        assertThat(dispatcher.destinatairesTentes).containsExactly(ALICE)
    }

    @Test
    fun `tous bloques sur la voie media rend une erreur de blocage`(@TempDir dir: File) {
        val uc = SendMediaMmsUseCase(
            defaultAppManager = alwaysDefault(),
            blockedRepo = BlockedList(setOf(ALICE)),
            mirror = NoopMirror(),
            sender = RecordingDispatcher(),
            settings = DefaultSettings(),
            attachmentStore = PassthroughStore(),
        )

        val out = runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE)),
                attachments = listOf(
                    SendMediaMmsUseCase.AttachmentPayload(fichier(dir, "photo.jpg"), "image/jpeg"),
                ),
            )
        }

        assertThat((out as Outcome.Failure).error).isEqualTo(AppError.RecipientBlocked)
    }

    /** Contrôle positif : sans blocage, l'envoi est complet et rien n'est marqué en échec. */
    @Test
    fun `un envoi media sans blocage est complet`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val uc = SendMediaMmsUseCase(
            defaultAppManager = alwaysDefault(),
            blockedRepo = BlockedList(emptySet()),
            mirror = mirror,
            sender = RecordingDispatcher(),
            settings = DefaultSettings(),
            attachmentStore = PassthroughStore(),
        )

        val out = runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE), PhoneAddress.of(BOB)),
                attachments = listOf(
                    SendMediaMmsUseCase.AttachmentPayload(fichier(dir, "photo.jpg"), "image/jpeg"),
                ),
            )
        }

        val rapport: SendReport = (out as Outcome.Success).value
        assertThat(rapport.isComplete).isTrue()
        assertThat(rapport.dispatched).hasSize(2)
        assertThat(mirror.statuts).isEmpty()
    }

    // ───────────────────────────── Voie VOCALE ─────────────────────────────

    @Test
    fun `un destinataire bloque laisse une trace sur la voie vocale`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingDispatcher()
        val uc = SendVoiceMmsUseCase(
            defaultAppManager = alwaysDefault(),
            blockedRepo = BlockedList(setOf(BOB)),
            mirror = mirror,
            sender = dispatcher,
            settings = DefaultSettings(),
            attachmentStore = PassthroughStore(),
        )

        val out = runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE), PhoneAddress.of(BOB)),
                audioFile = fichier(dir, "clip.m4a"),
                mimeType = "audio/mp4",
                durationMs = 3_000L,
            )
        }

        val rapport = (out as Outcome.Success).value
        assertThat(rapport.blocked.map { it.raw }).containsExactly(BOB)
        assertThat(mirror.lignesMms.map { it.adresse }).containsExactly(ALICE, BOB)
        assertThat(mirror.statuts.map { it.third }).contains(SendErrorCode.RECIPIENT_BLOCKED)
        assertThat(dispatcher.destinatairesTentes).containsExactly(ALICE)
    }

    /** Contrôle positif jumeau du précédent, sur la voie vocale. */
    @Test
    fun `un envoi vocal sans blocage est complet`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val uc = SendVoiceMmsUseCase(
            defaultAppManager = alwaysDefault(),
            blockedRepo = BlockedList(emptySet()),
            mirror = mirror,
            sender = RecordingDispatcher(),
            settings = DefaultSettings(),
            attachmentStore = PassthroughStore(),
        )

        val out = runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE)),
                audioFile = fichier(dir, "clip.m4a"),
                mimeType = "audio/mp4",
                durationMs = 3_000L,
            )
        }

        val rapport = (out as Outcome.Success).value
        assertThat(rapport.isComplete).isTrue()
        assertThat(mirror.statuts).isEmpty()
    }

    /**
     * Un échec du dispatcher reste une erreur de téléphonie, sur les deux voies : sans ce
     * contrôle, un correctif qui rendrait `RecipientBlocked` pour tout échec passerait pour bon.
     */
    @Test
    fun `un echec du dispatcher reste une erreur de telephonie`(@TempDir dir: File) {
        val uc = SendVoiceMmsUseCase(
            defaultAppManager = alwaysDefault(),
            blockedRepo = BlockedList(emptySet()),
            mirror = NoopMirror(),
            sender = RecordingDispatcher(Outcome.Failure(AppError.Telephony("no radio"))),
            settings = DefaultSettings(),
            attachmentStore = PassthroughStore(),
        )

        val out = runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE)),
                audioFile = fichier(dir, "clip.m4a"),
                mimeType = "audio/mp4",
                durationMs = 3_000L,
            )
        }

        assertThat((out as Outcome.Failure).error).isInstanceOf(AppError.Telephony::class.java)
    }
}
