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
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * v1.27.2 — **séquence de relances du Safety call**, décidée par Patrice le 2026-08-05 : le
 * déclenchement ne désarme plus le deadman sur-le-champ, trois relances suivent à quinze minutes
 * d'intervalle, et « Je vais bien » clôt la séquence.
 *
 * # Ce que ces tests protègent
 *
 * Deux propriétés, et elles tirent en sens opposés — c'est pour cela qu'il faut les tenir
 * ensemble :
 *
 *  1. **Rien ne doit partir en double.** Le tick périodique (60 min) et la relance ponctuelle
 *     (15 min) peuvent se croiser. Le créneau est donc réservé de façon atomique avant l'envoi.
 *  2. **Rien ne doit s'éteindre en silence.** Si aucun envoi n'aboutit — pas de réseau, mode
 *     avion, SIM absente — le créneau est **rendu** et le deadman reste armé. C'est le
 *     renversement du correctif SEC-3, qui désarmait avant même d'essayer : la protection
 *     s'éteignait exactement au moment où elle échouait.
 *
 * Faux écrits à la main : `:domain` n'a ni mockk ni Robolectric.
 */
class TriggerSafetyCallRelanceTest {

    private companion object {
        const val CONTACT = "+33611111111"
        const val TIMEOUT_MS = 3_600_000L
    }

    // ──────────────────────────── Faux ────────────────────────────

    /** Réglages en mémoire, avec un `update` réellement atomique — c'est ce qu'on teste. */
    private class FakeSettings(initial: AppSettings) : AppSettingsSource {
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
    private class CountingSender(var succeed: Boolean) : SmsSender {
        var calls = 0
        val bodies = mutableListOf<String>()
        override fun send(
            localMessageId: Long,
            destination: String,
            text: String,
            subId: Int?,
            requestDeliveryReport: Boolean,
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
    private class ClaimBarrierSettings(
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
    private class BeforeReservationSettings(private val store: FakeSettings) : AppSettingsSource {
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
    private class CancelAfterClaimSettings(private val store: FakeSettings) : AppSettingsSource {
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

    private class NoopRecorder : SentSmsRecorder {
        override fun insertSentSms(
            address: String,
            body: String,
            date: Long,
            threadId: Long?,
            subId: Int?,
        ): String = "content://sms/1"
    }

    private class NoopMirror : OutgoingMessageMirror {
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

    private class NeverBlocked : BlockedNumberRepository {
        override fun observe(): Flow<List<BlockedNumber>> = MutableStateFlow(emptyList())
        override suspend fun isBlocked(rawNumber: String): Boolean = false
        override suspend fun block(rawNumber: String, label: String?): Outcome<Unit> =
            Outcome.Success(Unit)
        override suspend fun unblock(rawNumber: String): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun mirrorFromSystem(rawNumber: String): Outcome<Unit> =
            Outcome.Success(Unit)
        override suspend fun blockedNormalizedSnapshot(): Set<String> = emptySet()
    }

    // ──────────────────────────── Montage ────────────────────────────

    /** Config armée et **déjà expirée** : le prochain appel doit déclencher. */
    private fun expiredSettings() = FakeSettings(
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
    private class ObservingSender(
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
    private class BlockingFirstSender : SmsSender {
        val reachedSend = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        private val first = AtomicBoolean(true)
        var calls = 0
        override fun send(
            localMessageId: Long,
            destination: String,
            text: String,
            subId: Int?,
            requestDeliveryReport: Boolean,
        ): Outcome<Unit> {
            calls++
            if (first.compareAndSet(true, false)) {
                reachedSend.complete(Unit)
                runBlocking { release.await() }
            }
            return Outcome.Success(Unit)
        }
    }

    private fun useCase(settings: AppSettingsSource, sender: SmsSender) = TriggerSafetyCallUseCase(
        sendSms = SendSmsUseCase(
            defaultAppManager = object : DefaultSmsAppChecker { override fun isDefault() = true },
            sentSmsRecorder = NoopRecorder(),
            sender = sender,
            mirror = NoopMirror(),
            blockedRepo = NeverBlocked(),
            settings = settings,
        ),
        settings = settings,
        panicState = object : PanicStateProvider { override val isPanicDecoyActive = false },
        io = Dispatchers.Unconfined,
    )

    /**
     * Fait comme si quinze minutes venaient de s'écouler, en reculant `triggeredAt` d'un
     * intervalle : la relance suivante devient due sans qu'aucun test n'ait à attendre.
     */
    private suspend fun rewindTriggeredAt(settings: FakeSettings) {
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

    // ──────────────────────────── Les tests ────────────────────────────

    @Test
    fun `le declenchement n arme plus le desarmement immediat`() {
        val settings = expiredSettings()
        val sender = CountingSender(succeed = true)

        val result = runBlocking { useCase(settings, sender).invoke() }

        assertThat(result).isInstanceOf(TriggerSafetyCallUseCase.Result.Triggered::class.java)
        assertThat(sender.calls).isEqualTo(1)
        // Le deadman RESTE arme : c'est toute la difference avec v1 (`enabled = false` d'emblee).
        assertThat(settings.safetyCall.enabled).isTrue()
        assertThat(settings.safetyCall.isTriggered).isTrue()
        assertThat(settings.safetyCall.messagesSent).isEqualTo(1)
        assertThat(settings.safetyCall.hasRelancePending).isTrue()
        // Et une relance est annoncee a l'appelant, qui la programmera.
        val triggered = result as TriggerSafetyCallUseCase.Result.Triggered
        assertThat(triggered.nextRelanceInMs).isEqualTo(SafetyCallConfig.RELANCE_INTERVAL_MS)
    }

    /**
     * **Le défaut que ce lot ferme.** Sans réseau, l'ancienne version désarmait quand même : elle
     * appelait `disableSafetyCall()` AVANT la boucle d'envoi. La protection s'éteignait
     * définitivement et en silence, au moment précis où elle échouait.
     */
    @Test
    fun `si aucun envoi n aboutit le creneau est rendu et le deadman reste arme`() {
        val settings = expiredSettings()
        val sender = CountingSender(succeed = false)

        val uc = useCase(settings, sender)
        val result = runBlocking { uc.invoke() }

        assertThat(result).isInstanceOf(TriggerSafetyCallUseCase.Result.SendFailed::class.java)
        assertThat(sender.calls).isEqualTo(1)
        // Rien ne doit avoir bouge : ni le desarmement, ni le compteur, ni l'horodatage.
        assertThat(settings.safetyCall.enabled).isTrue()
        assertThat(settings.safetyCall.isTriggered).isFalse()
        assertThat(settings.safetyCall.messagesSent).isEqualTo(0)
        // Donc le tick suivant retentera exactement le meme declenchement.
        assertThat(settings.safetyCall.isExpired()).isTrue()

        sender.succeed = true
        val retry = runBlocking { uc.invoke() }
        assertThat(retry).isInstanceOf(TriggerSafetyCallUseCase.Result.Triggered::class.java)
        assertThat(sender.calls).isEqualTo(2)
        assertThat(settings.safetyCall.messagesSent).isEqualTo(1)
        assertThat(settings.safetyCall.claimedAt).isEqualTo(0L)
    }

    @Test
    fun `deux ticks qui se croisent n envoient qu un seul message`() {
        val store = expiredSettings()
        val settings = ClaimBarrierSettings(store)
        val sender = CountingSender(succeed = true)
        val uc = useCase(settings, sender)

        runBlocking {
            val first = async(Dispatchers.Default) { uc.invoke() }
            val second = async(Dispatchers.Default) { uc.invoke() }
            val results = withTimeout(5_000L) { listOf(first.await(), second.await()) }

            assertThat(results.count { it is TriggerSafetyCallUseCase.Result.Triggered }).isEqualTo(1)
            assertThat(results.count { it is TriggerSafetyCallUseCase.Result.AlreadySent }).isEqualTo(1)
        }

        assertThat(sender.calls).isEqualTo(1)
        assertThat(store.safetyCall.messagesSent).isEqualTo(1)
        assertThat(store.safetyCall.claimedAt).isEqualTo(0L)
    }

    @Test
    fun `une annulation apres reservation avant envoi laisse un bail recuperable`() {
        val store = expiredSettings()
        val settings = CancelAfterClaimSettings(store)
        val sender = CountingSender(succeed = true)
        val uc = useCase(settings, sender)

        runBlocking {
            val running = async(Dispatchers.Default) { uc.invoke() }
            withTimeout(5_000L) { settings.afterClaim.await() }

            // Point exact de l'entrelacement : le compteur et le bail sont persistés, mais le
            // premier appel à SmsSender n'a pas encore eu lieu.
            assertThat(store.safetyCall.messagesSent).isEqualTo(1)
            assertThat(store.safetyCall.claimedAt).isGreaterThan(0L)
            assertThat(sender.calls).isEqualTo(0)

            running.cancelAndJoin()
            assertThat(running.isCancelled).isTrue()

            // Simule le prochain réveil après expiration du bail : le même créneau doit repartir.
            store.update { s ->
                s.copy(
                    security = s.security.copy(
                        safetyCall = s.security.safetyCall.copy(
                            claimedAt = System.currentTimeMillis() -
                                SafetyCallConfig.CLAIM_LEASE_MS - 1_000L,
                        ),
                    ),
                )
            }
            val retry = uc.invoke()
            assertThat(retry).isInstanceOf(TriggerSafetyCallUseCase.Result.Triggered::class.java)
        }

        assertThat(sender.calls).isEqualTo(1)
        assertThat(store.safetyCall.messagesSent).isEqualTo(1)
        assertThat(store.safetyCall.claimedAt).isEqualTo(0L)
    }

    @Test
    fun `la sequence complete envoie quatre messages puis desarme`() {
        val settings = expiredSettings()
        val sender = CountingSender(succeed = true)
        val uc = useCase(settings, sender)

        runBlocking {
            uc.invoke() // message initial
            repeat(SafetyCallConfig.RELANCE_COUNT) {
                rewindTriggeredAt(settings)
                uc.invoke()
            }
        }

        assertThat(sender.calls).isEqualTo(SafetyCallConfig.TOTAL_MESSAGES)
        // Les relances ne repetent PAS le message initial : chacune a son propre texte.
        assertThat(sender.bodies.toSet()).hasSize(SafetyCallConfig.TOTAL_MESSAGES)
        // La derniere annonce qu'elle est la derniere.
        assertThat(sender.bodies.last()).contains("Dernière alerte")
        // Sequence close : le deadman est desarme, sans relance en attente.
        assertThat(settings.safetyCall.enabled).isFalse()
        assertThat(settings.safetyCall.hasRelancePending).isFalse()
    }

    /**
     * **Le jumeau asymétrique** du test d'échec ci-dessus — et le motif qui a produit onze des
     * dix-sept correctifs du 2026-08-04, donc celui qu'il faut tenir des deux côtés.
     *
     * Sur un échec, le chemin **initial** remet `triggeredAt` à `0L` : rien n'est parti, la
     * séquence n'a pas commencé. Le chemin **relance**, lui, doit le **conserver** : le premier
     * message est déjà chez les contacts. L'effacer ferait repartir la séquence à zéro, et les
     * proches recevraient à nouveau le message d'origine — puis les relances — en boucle.
     */
    @Test
    fun `une relance qui echoue rend le creneau sans effacer le declenchement`() {
        val settings = expiredSettings()
        val sender = CountingSender(succeed = true)
        val uc = useCase(settings, sender)

        val result = runBlocking {
            uc.invoke() // message initial : parti, sequence ouverte
            rewindTriggeredAt(settings)
            sender.succeed = false // reseau perdu entre le message initial et la relance
            uc.invoke()
        }

        assertThat(result).isInstanceOf(TriggerSafetyCallUseCase.Result.SendFailed::class.java)
        // Non-vacuite : la relance a bien ete TENTEE, elle n'a pas ete court-circuitee.
        assertThat(sender.calls).isEqualTo(2)
        // Le creneau est rendu : la relance 1 sera retentee au tick suivant.
        assertThat(settings.safetyCall.messagesSent).isEqualTo(1)
        assertThat(settings.safetyCall.enabled).isTrue()
        // LE POINT : le declenchement n'est PAS efface, sinon la sequence repartirait a zero.
        assertThat(settings.safetyCall.isTriggered).isTrue()
    }

    /**
     * Le processus a pu être tué juste après le dernier envoi, avant que le désarmement ne
     * s'écrive. On finit le travail plutôt que de laisser un deadman armé qui, `triggeredAt` étant
     * posé et la séquence close, ne partirait plus jamais.
     */
    @Test
    fun `une sequence deja complete se referme sans renvoyer de message`() {
        val settings = expiredSettings()
        val sender = CountingSender(succeed = true)

        val result = runBlocking {
            settings.update { s ->
                s.copy(
                    security = s.security.copy(
                        safetyCall = s.security.safetyCall.copy(
                            triggeredAt = System.currentTimeMillis() -
                                SafetyCallConfig.TOTAL_MESSAGES * SafetyCallConfig.RELANCE_INTERVAL_MS,
                            messagesSent = SafetyCallConfig.TOTAL_MESSAGES,
                        ),
                    ),
                )
            }
            useCase(settings, sender).invoke()
        }

        assertThat(result).isEqualTo(TriggerSafetyCallUseCase.Result.SequenceComplete)
        assertThat(sender.calls).isEqualTo(0)
        assertThat(settings.safetyCall.enabled).isFalse()
    }

    /**
     * 🔴 **Le défaut trouvé par la relecture Gemini du 2026-08-05.**
     *
     * Le créneau est réservé — `messagesSent` incrémenté — **avant** l'envoi, pour qu'un tick
     * périodique et une relance ponctuelle qui se croiseraient n'envoient pas deux fois. Si le
     * processus meurt dans cet intervalle (mémoire insuffisante, mise à jour du système, batterie
     * critique), le tick suivant lisait `messagesSent = 1` et croyait le message initial parti.
     *
     * Les proches ne recevaient **jamais** le message qui explique la situation : ils
     * découvraient l'affaire par une relance. Ce n'était pas « au pire un doublon » — comme
     * l'affirmait mon commentaire — mais une **perte**.
     *
     * Le bail rend la réservation réversible : passé `CLAIM_LEASE_MS`, le créneau est repris et
     * l'envoi retenté dans la même exécution.
     */
    @Test
    fun `un creneau reserve mais jamais conclu est repris et le message initial part`() {
        val settings = expiredSettings()
        val sender = CountingSender(succeed = true)
        val abandonne = System.currentTimeMillis() - SafetyCallConfig.CLAIM_LEASE_MS - 1_000L

        val result = runBlocking {
            // L'état exact que laisse un processus tué entre la réservation et l'envoi.
            settings.update { s ->
                s.copy(
                    security = s.security.copy(
                        safetyCall = s.security.safetyCall.copy(
                            triggeredAt = abandonne,
                            messagesSent = 1,
                            claimedAt = abandonne,
                        ),
                    ),
                )
            }
            useCase(settings, sender).invoke()
        }

        assertThat(result).isInstanceOf(TriggerSafetyCallUseCase.Result.Triggered::class.java)
        assertThat(sender.calls).isEqualTo(1)
        // LE POINT : c'est le message INITIAL qui part, pas une relance. Sans la reprise, les
        // contacts auraient recu « toujours aucune activite, 15 minutes plus tard » en premier.
        assertThat(sender.bodies.single()).doesNotContain("15 minutes")
        assertThat(settings.safetyCall.messagesSent).isEqualTo(1)
        // Le bail est leve : sinon le creneau suivant serait bloque deux minutes et la relance
        // due dans cet intervalle serait sautee.
        assertThat(settings.safetyCall.claimedAt).isEqualTo(0L)
    }

    /**
     * Le versant opposé du bail : tant qu'il court, **rien d'autre ne part**. C'est la ceinture qui
     * double la comparaison de compteur, pour le cas où deux exécutions liraient le même
     * instantané.
     */
    @Test
    fun `un bail encore valide empeche un second envoi`() {
        val settings = expiredSettings()
        val sender = CountingSender(succeed = true)

        val result = runBlocking {
            settings.update { s ->
                s.copy(
                    security = s.security.copy(
                        safetyCall = s.security.safetyCall.copy(
                            // Relance 1 due depuis longtemps...
                            triggeredAt = System.currentTimeMillis() -
                                SafetyCallConfig.RELANCE_INTERVAL_MS - 1_000L,
                            messagesSent = 1,
                            // ...mais un envoi est en vol, bail pose a l instant.
                            claimedAt = System.currentTimeMillis(),
                        ),
                    ),
                )
            }
            useCase(settings, sender).invoke()
        }

        assertThat(result).isEqualTo(TriggerSafetyCallUseCase.Result.AlreadySent)
        assertThat(sender.calls).isEqualTo(0)
        assertThat(settings.safetyCall.messagesSent).isEqualTo(1)
    }

    /**
     * 🔴 **Le défaut SC-03 de l'audit Codex du 2026-08-05 : une fausse urgence juste après
     * « Je vais bien ».**
     *
     * La réservation ne comparait que `enabled` et `messagesSent`. Or une remise à zéro de
     * l'utilisateur — ouverture de l'application, tap sur la notification, bouton dédié — déplace
     * `lastActivityAt` **sans toucher au compteur**. Un worker parti avec un instantané d'avant la
     * confirmation réservait donc le créneau et envoyait quand même : les proches recevaient une
     * urgence à la seconde où la personne venait de confirmer aller bien.
     *
     * On simule l'entrelacement exact : le use-case lit un instantané expiré, puis le stockage
     * bouge sous lui avant qu'il ne réserve.
     */
    @Test
    fun `une remise a zero entre l instantane et la reservation annule l envoi`() {
        val store = expiredSettings()
        val interleaved = BeforeReservationSettings(store)
        val sender = CountingSender(succeed = true)
        val uc = useCase(interleaved, sender)

        val result = runBlocking {
            val worker = async(Dispatchers.Default) { uc.invoke() }
            // Le worker a lu son instantané expiré et attend juste avant sa transaction.
            withTimeout(5_000L) { interleaved.reservationReached.await() }
            store.update { s ->
                s.copy(
                    security = s.security.copy(
                        safetyCall = s.security.safetyCall.withActivityReset(),
                    ),
                )
            }
            interleaved.continueReservation.complete(Unit)
            withTimeout(5_000L) { worker.await() }
        }

        assertThat(result).isEqualTo(TriggerSafetyCallUseCase.Result.AlreadySent)
        // LE POINT : aucune fausse urgence n'est partie.
        assertThat(sender.calls).isEqualTo(0)
        assertThat(store.safetyCall.messagesSent).isEqualTo(0)
        assertThat(store.safetyCall.isTriggered).isFalse()
    }

    /**
     * 🔴 **La garde de non-régression de C-01** — le défaut que l'audit Codex a trouvé dans un
     * correctif vieux de vingt minutes.
     *
     * L'observateur de `MainApplication` annule le travail de relance dès qu'il juge la séquence
     * terminée. Il se fiait à `!hasRelancePending`. Or `messagesSent` compte les créneaux
     * **réservés** : à la réservation du 4ᵉ et dernier message, le compteur atteint
     * `TOTAL_MESSAGES` **avant le premier SMS**, et `hasRelancePending` devient faux
     * instantanément. L'observateur annulait donc le worker **pendant qu'il envoyait la dernière
     * alerte**.
     *
     * Ce test observe l'état persisté à l'instant précis de chaque envoi — c'est exactement ce
     * que l'observateur aurait vu — et exige qu'il ne soit JAMAIS terminal.
     */
    @Test
    fun `pendant chaque envoi, la sequence n est jamais vue comme terminee`() {
        val settings = expiredSettings()
        val vusPendantEnvoi = mutableListOf<SafetyCallConfig>()
        val sender = ObservingSender(settings) { vusPendantEnvoi += it }
        val uc = useCase(settings, sender)

        runBlocking {
            uc.invoke() // message initial
            repeat(SafetyCallConfig.RELANCE_COUNT) {
                rewindTriggeredAt(settings)
                uc.invoke()
            }
        }

        // Les quatre messages sont bien partis, dernier compris.
        assertThat(sender.calls).isEqualTo(SafetyCallConfig.TOTAL_MESSAGES)
        assertThat(vusPendantEnvoi).hasSize(SafetyCallConfig.TOTAL_MESSAGES)
        // LE POINT : a aucun moment l'observateur n'aurait annule le travail en cours.
        vusPendantEnvoi.forEachIndexed { index, cfg ->
            assertThat(cfg.claimedAt).isGreaterThan(0L)
            assertThat(cfg.isSequenceTerminal).isFalse()
            // Non-vacuite : au dernier envoi, `hasRelancePending` est bien FAUX. C'est
            // precisement l'etat sur lequel l'ancienne version se trompait.
            if (index == SafetyCallConfig.TOTAL_MESSAGES - 1) {
                assertThat(cfg.hasRelancePending).isFalse()
            }
        }
        // Et une fois tout conclu, l'etat DEVIENT terminal : sinon le test ci-dessus serait vide
        // de sens, un predicat toujours faux le satisferait aussi.
        assertThat(settings.safetyCall.isSequenceTerminal).isTrue()
    }

    /**
     * 🔴 **C-02** — un créneau réservé n'est pas une séquence terminée.
     *
     * État exact laissé entre la réservation du dernier message et sa conclusion :
     * `messagesSent = TOTAL_MESSAGES`, bail encore valide. Un second contrôle démarrant dans cette
     * fenêtre validait la séquence et **désarmait** : la dernière alerte n'aurait jamais été
     * retentée.
     */
    @Test
    fun `un dernier creneau en vol n est pas conclu par un second controle`() {
        val settings = expiredSettings()
        val sender = CountingSender(succeed = true)

        val result = runBlocking {
            settings.update { s ->
                s.copy(
                    security = s.security.copy(
                        safetyCall = s.security.safetyCall.copy(
                            triggeredAt = System.currentTimeMillis() -
                                SafetyCallConfig.TOTAL_MESSAGES * SafetyCallConfig.RELANCE_INTERVAL_MS,
                            messagesSent = SafetyCallConfig.TOTAL_MESSAGES,
                            // Bail pose a l instant : l envoi du dernier message est EN VOL.
                            claimedAt = System.currentTimeMillis(),
                            claimId = 7L,
                        ),
                    ),
                )
            }
            useCase(settings, sender).invoke()
        }

        assertThat(result).isEqualTo(TriggerSafetyCallUseCase.Result.AlreadySent)
        assertThat(sender.calls).isEqualTo(0)
        // LE POINT : le deadman n'est PAS desarme, le creneau reste au proprietaire en vol.
        assertThat(settings.safetyCall.enabled).isTrue()
        assertThat(settings.safetyCall.claimedAt).isGreaterThan(0L)
    }

    /**
     * 🔴 **C-03** — le bail a désormais un propriétaire.
     *
     * Un worker bloqué au-delà du bail voit son créneau repris, ce qui est voulu. Mais en revenant
     * tardivement il ne doit **rien** conclure : sans jeton de propriété, il restituait la
     * réservation du second — compteur ramené en arrière pendant un envoi réel — ou levait son
     * bail, ouvrant la porte à un troisième concurrent.
     */
    @Test
    fun `un worker revenu tard ne conclut pas le creneau d un autre`() {
        val settings = expiredSettings()
        // W1 est SUSPENDU dans sa boucle d'envoi, apres avoir persiste sa reservation. C'est le
        // seul etat ou le defaut existe : un bail pose, et son proprietaire encore vivant.
        val bloquant = BlockingFirstSender()
        val libre = CountingSender(succeed = true)

        runBlocking {
            val w1 = async(Dispatchers.Default) { useCase(settings, bloquant).invoke() }
            withTimeout(5_000L) { bloquant.reachedSend.await() }
            val claimW1 = settings.safetyCall.claimId
            assertThat(claimW1).isGreaterThan(0L)

            // Le bail de W1 expire : W2 est en droit de reprendre le creneau.
            settings.update { s ->
                s.copy(
                    security = s.security.copy(
                        safetyCall = s.security.safetyCall.copy(
                            claimedAt = System.currentTimeMillis() -
                                SafetyCallConfig.CLAIM_LEASE_MS - 1_000L,
                        ),
                    ),
                )
            }
            val w2 = useCase(settings, libre).invoke()
            assertThat(w2).isInstanceOf(TriggerSafetyCallUseCase.Result.Triggered::class.java)
            val apresW2 = settings.safetyCall
            assertThat(apresW2.claimId).isNotEqualTo(claimW1)

            // W1 revient enfin et tente de conclure SON creneau, qui ne lui appartient plus.
            bloquant.release.complete(Unit)
            val retardataire = withTimeout(5_000L) { w1.await() }

            assertThat(retardataire).isEqualTo(TriggerSafetyCallUseCase.Result.Superseded)
            // LE POINT : W1 n'a RIEN touche. Sans jeton de propriete, il levait le bail de W2 et
            // ramenait son compteur en arriere pendant un envoi reel.
            assertThat(settings.safetyCall.claimId).isEqualTo(apresW2.claimId)
            assertThat(settings.safetyCall.claimedAt).isEqualTo(apresW2.claimedAt)
            assertThat(settings.safetyCall.messagesSent).isEqualTo(apresW2.messagesSent)
            assertThat(settings.safetyCall.enabled).isEqualTo(apresW2.enabled)
        }
    }

    /**
     * 🔴 **C-04** — une confirmation « je vais bien » arrête l'envoi **en vol**.
     *
     * Comparer `lastActivityAt` fermait la fenêtre « instantané → réservation », pas
     * « réservation → envoi ». Quelqu'un qui confirmait aller bien pendant la boucle ne l'arrêtait
     * pas : les SMS d'urgence continuaient de partir vers ses proches.
     */
    @Test
    fun `une confirmation pendant la boucle d envoi arrete les envois suivants`() {
        val quatreContacts = List(4) { SafetyCallContact(phoneNumber = "+3361111111$it") }
        val settings = expiredSettings()
        runBlocking {
            settings.update { s ->
                s.copy(
                    security = s.security.copy(
                        safetyCall = s.security.safetyCall.copy(contacts = quatreContacts),
                    ),
                )
            }
        }
        // Au PREMIER envoi, l'utilisateur confirme aller bien : nouvelle generation de cycle.
        val sender = ObservingSender(settings) {
            runBlocking {
                if (settings.safetyCall.generation == 0L) {
                    settings.update { s ->
                        s.copy(
                            security = s.security.copy(
                                safetyCall = s.security.safetyCall.withActivityReset(),
                            ),
                        )
                    }
                }
            }
        }

        val result = runBlocking { useCase(settings, sender).invoke() }

        assertThat(result).isEqualTo(TriggerSafetyCallUseCase.Result.Superseded)
        // UN seul SMS est parti — celui deja en vol quand la confirmation est arrivee. Les trois
        // autres contacts n'ont RIEN recu.
        assertThat(sender.calls).isEqualTo(1)
        // Et le cycle tout neuf ouvert par la confirmation n'a ete ni desarme, ni pollue.
        assertThat(settings.safetyCall.enabled).isTrue()
        assertThat(settings.safetyCall.isTriggered).isFalse()
        assertThat(settings.safetyCall.messagesSent).isEqualTo(0)
        assertThat(settings.safetyCall.claimedAt).isEqualTo(0L)
    }

    @Test
    fun `une relance non due n envoie rien`() {
        val settings = expiredSettings()
        val sender = CountingSender(succeed = true)
        val uc = useCase(settings, sender)

        runBlocking {
            uc.invoke() // message initial, `triggeredAt` = maintenant
            val second = uc.invoke() // 15 min ne sont pas ecoulees
            assertThat(second).isEqualTo(TriggerSafetyCallUseCase.Result.NotExpired)
        }

        assertThat(sender.calls).isEqualTo(1)
    }
}
