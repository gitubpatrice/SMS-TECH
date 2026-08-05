package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.mms.MediaAttachmentSpec
import com.filestech.sms.domain.model.BlockedNumber
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.repository.BlockedNumberRepository
import com.filestech.sms.domain.repository.OutgoingMessageMirror
import com.filestech.sms.domain.sender.DefaultSmsAppChecker
import com.filestech.sms.domain.sender.SentSmsRecorder
import com.filestech.sms.domain.sender.SmsSender
import com.filestech.sms.domain.settings.AppSettingsSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.File

/**
 * Faux collaborateurs de [SendSmsUseCase], partagés par les tests qui l'exercent directement
 * ([SendSmsSettingsFallbackTest]) et par ceux qui l'exercent à travers un use-case appelant
 * ([TriggerSafetyCallRelanceTest]).
 *
 * `:domain` n'a **ni mockk ni Robolectric**, et ne doit pas en gagner pour cinq interfaces à une
 * méthode. Écrits à la main, donc — mais une seule fois.
 */

/**
 * Enregistre chaque envoi et laisse le test choisir son issue.
 *
 * [outcome] est ce qui permet de tester le **sens dans lequel l'envoi échoue** : un Safety call
 * dont aucun message ne part ne doit pas se désarmer.
 */
internal class RecordingSender(
    var outcome: Outcome<Unit> = Outcome.Success(Unit),
) : SmsSender {
    val sentTexts = mutableListOf<String>()
    var lastSubId: Int? = null
    var lastText: String? = null
    var lastDeliveryReport: Boolean? = null
    val callCount: Int get() = sentTexts.size

    override fun send(
        localMessageId: Long,
        destination: String,
        text: String,
        subId: Int?,
        requestDeliveryReport: Boolean,
    ): Outcome<Unit> {
        sentTexts += text
        lastSubId = subId
        lastText = text
        lastDeliveryReport = requestDeliveryReport
        return outcome
    }
}

internal class RecordingRecorder : SentSmsRecorder {
    var lastSubId: Int? = null
    override fun insertSentSms(
        address: String,
        body: String,
        date: Long,
        threadId: Long?,
        subId: Int?,
    ): String? {
        lastSubId = subId
        return "content://sms/1"
    }
}

internal class NoopMirror : OutgoingMessageMirror {
    override suspend fun upsertOutgoingSms(
        address: String,
        body: String,
        date: Long,
        telephonyUri: String?,
        subId: Int?,
        initialStatus: MessageStatus,
        replyToMessageId: Long?,
        localMirrorBody: String?,
    ): Long = 1L

    override suspend fun updateOutgoingStatus(
        localId: Long,
        status: MessageStatus,
        errorCode: Int?,
    ) = Unit

    // Non exercees par SendSmsUseCase, mais l'interface les impose.
    override suspend fun resetOutgoingForRetry(localId: Long) = Unit

    override suspend fun upsertOutgoingMms(
        address: String,
        audioFile: File,
        mimeType: String,
        durationMs: Long,
        date: Long,
        subId: Int?,
    ): Long = error("non utilise")

    override suspend fun upsertOutgoingMediaMms(
        address: String,
        attachments: List<MediaAttachmentSpec>,
        textBody: String,
        date: Long,
        subId: Int?,
    ): Long = error("non utilise")
}

internal class NeverBlocked : BlockedNumberRepository {
    override fun observe(): Flow<List<BlockedNumber>> = flowOf(emptyList())
    override suspend fun isBlocked(rawNumber: String): Boolean = false
    override suspend fun block(rawNumber: String, label: String?): Outcome<Unit> =
        Outcome.Success(Unit)
    override suspend fun unblock(rawNumber: String): Outcome<Unit> = Outcome.Success(Unit)
    override suspend fun mirrorFromSystem(rawNumber: String): Outcome<Unit> =
        Outcome.Success(Unit)
    override suspend fun blockedNormalizedSnapshot(): Set<String> = emptySet()
    override suspend fun blockedRawSnapshot(): List<String> = emptyList()
}

/** Assemble un [SendSmsUseCase] réel autour des faux ci-dessus. */
internal fun sendSmsUseCase(
    settings: AppSettingsSource,
    sender: SmsSender,
    recorder: SentSmsRecorder = RecordingRecorder(),
    isDefaultSmsApp: Boolean = true,
) = SendSmsUseCase(
    defaultAppManager = object : DefaultSmsAppChecker {
        override fun isDefault() = isDefaultSmsApp
    },
    sentSmsRecorder = recorder,
    sender = sender,
    mirror = NoopMirror(),
    blockedRepo = NeverBlocked(),
    settings = settings,
)

/** Échec d'envoi côté radio — SIM absente, mode avion, pas de réseau. */
internal fun radioFailure(): Outcome<Unit> = Outcome.Failure(AppError.Telephony("no radio"))
