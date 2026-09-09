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
        attempt: Int,
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

    /** v1.28.3 (F21) — rien ne doit etre ecrit chez le fournisseur systeme pour un bloque. */
    val adressesEcrites = mutableListOf<String>()

    override fun insertSentSms(
        address: String,
        body: String,
        date: Long,
        threadId: Long?,
        subId: Int?,
    ): String? {
        lastSubId = subId
        adressesEcrites += address
        return "content://sms/1"
    }
}

internal class NoopMirror : OutgoingMessageMirror {
    /**
     * v1.28.3 (F21) — les lignes ecrites sont desormais RETENUES : c'est la trace qu'un
     * destinataire bloque ne laissait pas, et il faut donc pouvoir la mesurer.
     */
    data class LigneEcrite(val adresse: String, val telephonyUri: String?, val statut: MessageStatus)

    val lignes = mutableListOf<LigneEcrite>()
    val statuts = mutableListOf<Triple<Long, MessageStatus, Int?>>()
    private var prochainId = 1L

    override suspend fun upsertOutgoingSms(
        address: String,
        body: String,
        date: Long,
        telephonyUri: String?,
        subId: Int?,
        initialStatus: MessageStatus,
        replyToMessageId: Long?,
        localMirrorBody: String?,
    ): Long {
        lignes += LigneEcrite(address, telephonyUri, initialStatus)
        return prochainId++
    }

    override suspend fun updateOutgoingStatus(
        localId: Long,
        status: MessageStatus,
        errorCode: Int?,
        attempt: Int?,
    ) {
        statuts += Triple(localId, status, errorCode)
    }

    /**
     * v1.28.3 (F05) — le radio a toujours confirme, c'est-a-dire le cas nominal. Les tests qui
     * veulent mesurer un envoi NON confirme fournissent leur propre doublure.
     */
    override suspend fun outgoingStatus(localId: Long): MessageStatus = MessageStatus.SENT

    // Non exercees par SendSmsUseCase, mais l'interface les impose.
    override suspend fun resetOutgoingForRetry(localId: Long): Int = 1

    /**
     * v1.28.3 (F21, second passage) — les deux voies MMS ecrivent ici, elles aussi. Elles
     * jetaient `error("non utilise")` : c'etait vrai tant que seuls les tests de la voie SMS
     * existaient, et cette affirmation-la a vieilli comme les autres.
     */
    data class LigneMms(val adresse: String, val piecesJointes: Int)

    val lignesMms = mutableListOf<LigneMms>()

    override suspend fun upsertOutgoingMms(
        address: String,
        audioFile: File,
        mimeType: String,
        durationMs: Long,
        date: Long,
        subId: Int?,
    ): Long {
        lignesMms += LigneMms(address, 1)
        return prochainId++
    }

    override suspend fun upsertOutgoingMediaMms(
        address: String,
        attachments: List<MediaAttachmentSpec>,
        textBody: String,
        date: Long,
        subId: Int?,
    ): Long {
        lignes += LigneEcrite(address, telephonyUri = null, statut = MessageStatus.PENDING)
        lignesMms += LigneMms(address, attachments.size)
        return prochainId++
    }
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
    mirror: OutgoingMessageMirror = NoopMirror(),
    blocked: BlockedNumberRepository = NeverBlocked(),
) = SendSmsUseCase(
    defaultAppManager = object : DefaultSmsAppChecker {
        override fun isDefault() = isDefaultSmsApp
    },
    sentSmsRecorder = recorder,
    sender = sender,
    mirror = mirror,
    blockedRepo = blocked,
    settings = settings,
)

/** v1.28.3 (F21) — bloque les numeros dont la forme brute figure dans [numeros]. */
internal class BlockedList(private val numeros: Set<String>) : BlockedNumberRepository {
    override fun observe(): Flow<List<BlockedNumber>> = flowOf(emptyList())
    override suspend fun isBlocked(rawNumber: String): Boolean = rawNumber in numeros
    override suspend fun block(rawNumber: String, label: String?): Outcome<Unit> = Outcome.Success(Unit)
    override suspend fun unblock(rawNumber: String): Outcome<Unit> = Outcome.Success(Unit)
    override suspend fun mirrorFromSystem(rawNumber: String): Outcome<Unit> = Outcome.Success(Unit)
    override suspend fun blockedNormalizedSnapshot(): Set<String> = emptySet()
    override suspend fun blockedRawSnapshot(): List<String> = numeros.toList()
}

/** Échec d'envoi côté radio — SIM absente, mode avion, pas de réseau. */
internal fun radioFailure(): Outcome<Unit> = Outcome.Failure(AppError.Telephony("no radio"))
