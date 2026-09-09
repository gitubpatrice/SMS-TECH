package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.mms.MediaAttachmentSpec
import com.filestech.sms.domain.model.BlockedNumber
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.repository.BlockedNumberRepository
import com.filestech.sms.domain.repository.OutgoingMessageMirror
import com.filestech.sms.domain.safetycall.SafetyCallConfig
import com.filestech.sms.domain.safetycall.SafetyCallContact
import com.filestech.sms.domain.security.PanicStateProvider
import com.filestech.sms.domain.sender.DefaultSmsAppChecker
import com.filestech.sms.domain.sender.SentSmsRecorder
import com.filestech.sms.domain.sender.SmsSender
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * v1.28.3 — doublures partagées des tests de l'appel de sécurité.
 *
 * Extraites de `TriggerSafetyCallRelanceTest`, qui les portait en classes imbriquées : dès qu'un
 * second fichier de test en a eu besoin, les garder là aurait imposé de les dupliquer. Même
 * motif et même remède que [SendSmsFakes] côté `:domain`.
 *
 * `:domain` n'ayant ni mockk ni Robolectric, tout est écrit à la main.
 */
internal const val CONTACT = "+33611111111"
internal const val TIMEOUT_MS = 3_600_000L

// ──────────────────────────── Faux ────────────────────────────

/** Réglages en mémoire, avec un `update` réellement atomique — c'est ce qu'on teste. */
internal class FakeSettings(initial: AppSettings) : AppSettingsSource {
    private val _state = MutableStateFlow(initial)
    private val lock = Mutex()
    override val flow: Flow<AppSettings> = _state.asStateFlow()
    override val state: StateFlow<AppSettings> = _state.asStateFlow()
    override suspend fun hydratedOrNull(): AppSettings = _state.value
    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        lock.withLock { _state.value = transform(_state.value) }
    }

    val safetyCall get() = _state.value.security.safetyCall
}

/**
 * [succeed] est **mutable** : c'est ce qui permet de faire échouer une relance APRÈS un premier
 * envoi réussi, donc de tester le chemin d'échec de la séquence — celui où `triggeredAt` ne
 * doit surtout pas être effacé.
 */
internal class CountingSender(var succeed: Boolean) : SmsSender {
    var calls = 0
    val bodies = mutableListOf<String>()
    override fun send(
        localMessageId: Long,
        destination: String,
        text: String,
        subId: Int?,
        requestDeliveryReport: Boolean,
        attempt: Int,
    ): Outcome<Unit> {
        calls++
        bodies += text
        return if (succeed) {
            Outcome.Success(Unit)
        } else {
            Outcome.Failure(AppError.Telephony("réseau indisponible"))
        }
    }
}

/** Force deux appels à atteindre leur première transaction avant d'en laisser passer un. */
internal class ClaimBarrierSettings(
    private val store: FakeSettings,
    private val parties: Int = 2,
) : AppSettingsSource {
    private val arrivals = AtomicInteger(0)
    private val barrier = CompletableDeferred<Unit>()

    override val flow: Flow<AppSettings> = store.flow
    override val state: StateFlow<AppSettings> = store.state
    override suspend fun hydratedOrNull(): AppSettings = store.hydratedOrNull()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        val position = arrivals.incrementAndGet()
        if (position <= parties) {
            if (position == parties) barrier.complete(Unit)
            barrier.await()
        }
        store.update(transform)
    }
}

/** Suspend la première transaction après la lecture du snapshot, avant la réservation. */
internal class BeforeReservationSettings(private val store: FakeSettings) : AppSettingsSource {
    private val firstUpdate = AtomicBoolean(true)
    val reservationReached = CompletableDeferred<Unit>()
    val continueReservation = CompletableDeferred<Unit>()

    override val flow: Flow<AppSettings> = store.flow
    override val state: StateFlow<AppSettings> = store.state
    override suspend fun hydratedOrNull(): AppSettings = store.hydratedOrNull()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        if (firstUpdate.compareAndSet(true, false)) {
            reservationReached.complete(Unit)
            continueReservation.await()
        }
        store.update(transform)
    }
}

/** Bloque le premier passage dans l'envoi, nécessairement après la réservation persistée. */
internal class CancelAfterClaimSettings(private val store: FakeSettings) : AppSettingsSource {
    private val blockFirstSend = AtomicBoolean(true)
    val afterClaim = CompletableDeferred<Unit>()

    override val flow: Flow<AppSettings> = store.flow
    override val state: StateFlow<AppSettings> = store.state

    override suspend fun hydratedOrNull(): AppSettings {
        if (blockFirstSend.compareAndSet(true, false)) {
            afterClaim.complete(Unit)
            awaitCancellation()
        }
        return store.hydratedOrNull()
    }

    override suspend fun update(transform: (AppSettings) -> AppSettings) = store.update(transform)
}

internal class NoopRecorder : SentSmsRecorder {
    override fun insertSentSms(
        address: String,
        body: String,
        date: Long,
        threadId: Long?,
        subId: Int?,
    ): String = "content://sms/1"
}

/**
 * v1.28.3 (F05) — la doublure rend desormais des identifiants CROISSANTS et un statut
 * parametrable.
 *
 * Les deux comptent. L'identifiant fixe `1L` faisait passer trois envois pour un seul des
 * que le code les rapprochait par identifiant ; et [statutRendu] est ce qui permet de
 * distinguer « remis a `SmsManager` » de « parti », c'est-a-dire de mesurer F05.
 */
internal class NoopMirror(
    private val statutRendu: MessageStatus = MessageStatus.SENT,
) : OutgoingMessageMirror {
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
    ): Long = prochainId++

    override suspend fun updateOutgoingStatus(
        localId: Long,
        status: MessageStatus,
        errorCode: Int?,
        attempt: Int?,
    ) = true

    override suspend fun outgoingStatus(localId: Long): MessageStatus = statutRendu

    override suspend fun resetOutgoingForRetry(localId: Long): Int = 1

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
    override fun observe(): Flow<List<BlockedNumber>> = MutableStateFlow(emptyList())
    override suspend fun isBlocked(rawNumber: String): Boolean = false
    override suspend fun block(rawNumber: String, label: String?): Outcome<Unit> =
        Outcome.Success(Unit)
    override suspend fun unblock(rawNumber: String): Outcome<Unit> = Outcome.Success(Unit)
    override suspend fun mirrorFromSystem(rawNumber: String): Outcome<Unit> =
        Outcome.Success(Unit)
    override suspend fun blockedNormalizedSnapshot(): Set<String> = emptySet()
    override suspend fun blockedRawSnapshot(): List<String> = emptyList()
}

// ──────────────────────────── Montage ────────────────────────────

/** Config armée et **déjà expirée** : le prochain appel doit déclencher. */
internal fun expiredSettings() = FakeSettings(
    AppSettings().let { base ->
        base.copy(
            security = base.security.copy(
                safetyCall = SafetyCallConfig(
                    enabled = true,
                    timeoutMs = TIMEOUT_MS,
                    lastActivityAt = System.currentTimeMillis() - TIMEOUT_MS * 2,
                    monotonicLastActivityAt = 1L,
                    monotonicAccumulatedMs = TIMEOUT_MS * 2,
                    contacts = listOf(SafetyCallContact(phoneNumber = CONTACT)),
                ),
            ),
        )
    },
)

/**
 * Expéditeur qui **observe l'état persisté au moment exact de l'envoi**. C'est le seul point
 * d'observation qui permette de tester C-01 sans monter WorkManager : la question est de
 * savoir ce que l'observateur de `MainApplication` aurait vu pendant que le SMS partait.
 */
internal class ObservingSender(
    private val store: FakeSettings,
    private val onSend: (SafetyCallConfig) -> Unit,
) : SmsSender {
    var calls = 0
    override fun send(
        localMessageId: Long,
        destination: String,
        text: String,
        subId: Int?,
        requestDeliveryReport: Boolean,
        attempt: Int,
    ): Outcome<Unit> {
        calls++
        onSend(store.safetyCall)
        return Outcome.Success(Unit)
    }
}

/**
 * Suspend le **premier** envoi une fois la réservation déjà persistée. C'est le seul état où
 * le défaut C-03 existe : un bail posé, et son propriétaire toujours vivant.
 */
internal class BlockingFirstSender : SmsSender {
    val reachedSend = CompletableDeferred<Unit>()

    /**
     * ⚠️ **À libérer dans un `finally`.** L'attente vit dans un `runBlocking` imbriqué, sur un
     * fil de `Dispatchers.Default` : l'annulation du `runBlocking` extérieur ne la traverse
     * pas. Une assertion qui échoue avant la libération ne fait donc PAS échouer le test — elle
     * le fait **rester bloqué**, et avec lui toute la suite. Constaté le 2026-08-05 en tentant
     * de prouver la non-vacuité de P-01 : la preuve n'a jamais rendu la main.
     */
    val release = CompletableDeferred<Unit>()
    private val first = AtomicBoolean(true)
    var calls = 0
    override fun send(
        localMessageId: Long,
        destination: String,
        text: String,
        subId: Int?,
        requestDeliveryReport: Boolean,
        attempt: Int,
    ): Outcome<Unit> {
        calls++
        if (first.compareAndSet(true, false)) {
            reachedSend.complete(Unit)
            runBlocking { release.await() }
        }
        return Outcome.Success(Unit)
    }
}

/**
 * v1.28.3 (F05) — [mirror] est la MEME instance des deux cotes. `SendSmsUseCase` y ecrit la
 * ligne et rend son identifiant ; `TriggerSafetyCallUseCase` y relit le statut. Deux
 * instances distinctes rendraient le test complaisant : les identifiants ne se
 * correspondraient pas et la confirmation porterait sur des lignes imaginaires.
 *
 * [io] reste `Unconfined` par defaut — les tests existants rendent `SENT` du premier coup,
 * donc l'attente sort sans jamais appeler `delay`. Un test qui veut mesurer un radio muet
 * doit passer le dispatcher de `runTest`, sinon l'attente dure quarante-cinq secondes
 * REELLES.
 */
internal fun useCase(
    settings: AppSettingsSource,
    sender: SmsSender,
    mirror: NoopMirror = NoopMirror(),
    io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
) = TriggerSafetyCallUseCase(
    sendSms = SendSmsUseCase(
        defaultAppManager = object : DefaultSmsAppChecker { override fun isDefault() = true },
        sentSmsRecorder = NoopRecorder(),
        sender = sender,
        mirror = mirror,
        blockedRepo = NeverBlocked(),
        settings = settings,
    ),
    settings = settings,
    panicState = object : PanicStateProvider { override val isPanicDecoyActive = false },
    mirror = mirror,
    io = io,
)

/**
 * Fait comme si quinze minutes venaient de s'écouler, en reculant `triggeredAt` d'un
 * intervalle : la relance suivante devient due sans qu'aucun test n'ait à attendre.
 */
internal suspend fun rewindTriggeredAt(settings: FakeSettings) {
    settings.update { s ->
        s.copy(
            security = s.security.copy(
                safetyCall = s.security.safetyCall.copy(
                    triggeredAt = s.security.safetyCall.triggeredAt -
                        SafetyCallConfig.RELANCE_INTERVAL_MS,
                ),
            ),
        )
    }
}
