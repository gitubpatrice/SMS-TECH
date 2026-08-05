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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.jupiter.api.Test
import java.io.File

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

    private fun useCase(settings: FakeSettings, sender: SmsSender) = TriggerSafetyCallUseCase(
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

        val result = runBlocking { useCase(settings, sender).invoke() }

        assertThat(result).isInstanceOf(TriggerSafetyCallUseCase.Result.SendFailed::class.java)
        assertThat(sender.calls).isEqualTo(1)
        // Rien ne doit avoir bouge : ni le desarmement, ni le compteur, ni l'horodatage.
        assertThat(settings.safetyCall.enabled).isTrue()
        assertThat(settings.safetyCall.isTriggered).isFalse()
        assertThat(settings.safetyCall.messagesSent).isEqualTo(0)
        // Donc le tick suivant retentera exactement le meme declenchement.
        assertThat(settings.safetyCall.isExpired()).isTrue()
    }

    @Test
    fun `deux ticks qui se croisent n envoient qu un seul message`() {
        val settings = expiredSettings()
        val sender = CountingSender(succeed = true)
        val uc = useCase(settings, sender)

        runBlocking {
            uc.invoke()
            // Second appel avec le MEME instantane de depart : c'est le tick periodique qui
            // arrive pendant que la relance ponctuelle vient de passer.
            val second = uc.invoke()
            assertThat(second).isEqualTo(TriggerSafetyCallUseCase.Result.NotExpired)
        }

        assertThat(sender.calls).isEqualTo(1)
        assertThat(settings.safetyCall.messagesSent).isEqualTo(1)
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
        assertThat(sender.bodies.last()).contains("Dernier message")
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
