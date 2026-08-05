package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.mms.MediaAttachmentSpec
import com.filestech.sms.domain.model.BlockedNumber
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.BlockedNumberRepository
import com.filestech.sms.domain.repository.OutgoingMessageMirror
import com.filestech.sms.domain.sender.DefaultSmsAppChecker
import com.filestech.sms.domain.sender.SentSmsRecorder
import com.filestech.sms.domain.sender.SmsSender
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.settings.ConversationSettings
import com.filestech.sms.domain.settings.SendingSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File

/**
 * v1.27.2 — fige le **sens dans lequel l'envoi échoue** quand les réglages sont illisibles.
 *
 * # Le contexte
 *
 * `SendSmsUseCase` lisait `settings.state.value`, un instantané qui rend les valeurs PAR DÉFAUT
 * tant que le processus n'a pas hydraté DataStore. Or cet envoi n'est pas toujours déclenché
 * depuis l'interface : le Safety call et le mode urgence y passent depuis un **worker réveillé à
 * froid**, la réponse rapide depuis une notification aussi. `defaultSubId` y valait `null`, donc
 * le SMS d'urgence partait de la SIM système au lieu de celle choisie — d'un numéro que les
 * contacts ne reconnaissent pas — et sans la signature.
 *
 * # Ce que ces tests verrouillent
 *
 * Le passage à `hydratedOrNull()` introduit une asymétrie **délibérée** : ici, contrairement à
 * [com.filestech.sms.system.notifications.IncomingMessageNotifier], le repli va aux valeurs par
 * défaut plutôt qu'au refus. La raison : des réglages manquants ne dégradent que du confort
 * (pas de signature, SIM système, pas d'accusé), tandis que refuser l'envoi coûterait **le
 * message lui-même — y compris un message d'urgence**.
 *
 * Sans ces tests, ce choix ne repose que sur un commentaire. Le premier prouve que l'envoi part
 * quand même ; le second prouve que les réglages réels sont bien appliqués quand ils existent,
 * sans quoi le premier serait satisfait par un use-case qui les ignorerait toujours.
 *
 * Faux écrits à la main : `:domain` n'a ni mockk ni Robolectric, et ne doit pas en gagner pour
 * cinq interfaces à une méthode.
 */
class SendSmsSettingsFallbackTest {

    private companion object {
        const val CHOSEN_SUB_ID = 7
        const val SIGNATURE = "Patrice"
        const val BODY = "Message d'urgence"
        const val RECIPIENT = "+33611111111"
    }

    // ──────────────────────────── Faux ────────────────────────────

    private class RecordingSender : SmsSender {
        var lastSubId: Int? = null
        var lastText: String? = null
        var lastDeliveryReport: Boolean? = null
        var callCount = 0

        override fun send(
            localMessageId: Long,
            destination: String,
            text: String,
            subId: Int?,
            requestDeliveryReport: Boolean,
        ): Outcome<Unit> {
            callCount++
            lastSubId = subId
            lastText = text
            lastDeliveryReport = requestDeliveryReport
            return Outcome.Success(Unit)
        }
    }

    private class RecordingRecorder : SentSmsRecorder {
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

    private class NeverBlocked : BlockedNumberRepository {
        override fun observe(): Flow<List<BlockedNumber>> = flowOf(emptyList())
        override suspend fun isBlocked(rawNumber: String): Boolean = false
        override suspend fun block(rawNumber: String, label: String?): Outcome<Unit> =
            Outcome.Success(Unit)
        override suspend fun unblock(rawNumber: String): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun mirrorFromSystem(rawNumber: String): Outcome<Unit> =
            Outcome.Success(Unit)
        override suspend fun blockedNormalizedSnapshot(): Set<String> = emptySet()
    }

    /**
     * Le faux qui porte tout l'enjeu : [hydratedOrNull] rend `null` — réglages illisibles — alors
     * que [state] rend, comme en production, les valeurs par DÉFAUT.
     */
    private class UnreadableSettings : AppSettingsSource {
        override val flow: Flow<AppSettings> = flowOf(AppSettings())
        override val state: StateFlow<AppSettings> = MutableStateFlow(AppSettings())
        override suspend fun hydratedOrNull(): AppSettings? = null
        override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
    }

    private class RealSettings(private val value: AppSettings) : AppSettingsSource {
        override val flow: Flow<AppSettings> = flowOf(value)

        // Volontairement les DÉFAUTS : si le use-case lisait encore `state`, les tests
        // ci-dessous le verraient immédiatement.
        override val state: StateFlow<AppSettings> = MutableStateFlow(AppSettings())
        override suspend fun hydratedOrNull(): AppSettings = value
        override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
    }

    private fun useCase(
        settings: AppSettingsSource,
        sender: SmsSender,
        recorder: SentSmsRecorder,
    ) = SendSmsUseCase(
        defaultAppManager = object : DefaultSmsAppChecker { override fun isDefault() = true },
        sentSmsRecorder = recorder,
        sender = sender,
        mirror = NoopMirror(),
        blockedRepo = NeverBlocked(),
        settings = settings,
    )

    // ──────────────────────────── Les tests ────────────────────────────

    @Test
    fun `des reglages illisibles n'empechent pas l'envoi`() {
        val sender = RecordingSender()
        val recorder = RecordingRecorder()

        val outcome = runBlocking {
            useCase(UnreadableSettings(), sender, recorder)
                .invoke(recipients = listOf(PhoneAddress.of(RECIPIENT)), body = BODY)
        }

        // C'est le point : le message part. Refuser l'envoi coûterait le message lui-même,
        // alerte d'urgence comprise.
        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        assertThat(sender.callCount).isEqualTo(1)
        // Repli assumé : pas de signature, SIM système, pas d'accusé de réception.
        assertThat(sender.lastText).isEqualTo(BODY)
        assertThat(sender.lastSubId).isNull()
        assertThat(sender.lastDeliveryReport).isFalse()
    }

    @Test
    fun `des reglages lisibles sont bien appliques`() {
        val sender = RecordingSender()
        val recorder = RecordingRecorder()
        val configured = AppSettings(
            conversations = ConversationSettings(signature = SIGNATURE),
            sending = SendingSettings(defaultSubId = CHOSEN_SUB_ID, deliveryReports = true),
        )

        val outcome = runBlocking {
            useCase(RealSettings(configured), sender, recorder)
                .invoke(recipients = listOf(PhoneAddress.of(RECIPIENT)), body = BODY)
        }

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        // Non-vacuité du test précédent : sans ces assertions, un use-case qui ignorerait
        // TOUJOURS les réglages passerait le premier test sans rien prouver.
        assertThat(sender.lastSubId).isEqualTo(CHOSEN_SUB_ID)
        assertThat(sender.lastDeliveryReport).isTrue()
        assertThat(sender.lastText).contains(SIGNATURE)
        // La ligne système doit porter la MÊME SIM que la radio, sinon le fil se dédouble.
        assertThat(recorder.lastSubId).isEqualTo(CHOSEN_SUB_ID)
    }
}
