package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.emergency.EmergencyConfig
import com.filestech.sms.domain.location.GeoLocation
import com.filestech.sms.domain.location.LocationProvider
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.SimActive
import com.filestech.sms.domain.repository.OutgoingMessageMirror
import com.filestech.sms.domain.safetycall.SafetyCallContact
import com.filestech.sms.domain.security.PanicStateProvider
import com.filestech.sms.domain.sender.SimsActives
import com.filestech.sms.domain.sender.SmsSender
import com.filestech.sms.domain.settings.AppSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * 2026-11-16 (v1.31.0) — **le mode urgence rend compte de la bascule de SIM.**
 *
 * [EnvoiDAlerte] porte la règle ; ce test tient ce que le mode urgence en fait : les contacts servis par l'autre SIM
 * sont annoncés ([TriggerEmergencyUseCase.Result.Triggered.parAutreSim]), et un message encore sans verdict compte
 * comme envoyé, comme avant la 1.31.0, où l'acceptation par `SmsManager` suffisait.
 */
class AlerteUrgenceAutreSimTest {

    private val deuxSims = object : SimsActives {
        override fun lister() = listOf(SimActive(11, 0, "Orange"), SimActive(22, 1, "Free"))
        override fun simSmsDAndroid(): Int? = 11
    }

    private class Expediteur : SmsSender {
        val sims = mutableListOf<Int?>()
        override fun send(
            localMessageId: Long,
            destination: String,
            text: String,
            subId: Int?,
            requestDeliveryReport: Boolean,
            attempt: Int,
        ): Outcome<Unit> {
            sims += subId
            return Outcome.Success(Unit)
        }
    }

    /** Verdict de la radio selon la SIM de la ligne. */
    private class Radio(
        private val echec: Set<Int?> = emptySet(),
        private val muette: Set<Int?> = emptySet(),
        private val base: NoopMirror = NoopMirror(),
    ) : OutgoingMessageMirror by base {
        private val simDeLaLigne = mutableMapOf<Long, Int?>()

        override suspend fun upsertOutgoingSms(
            address: String,
            body: String,
            date: Long,
            telephonyUri: String?,
            subId: Int?,
            initialStatus: MessageStatus,
            replyToMessageId: Long?,
            localMirrorBody: String?,
        ): Long = base.upsertOutgoingSms(
            address,
            body,
            date,
            telephonyUri,
            subId,
            initialStatus,
            replyToMessageId,
            localMirrorBody,
        ).also { simDeLaLigne[it] = subId }

        override suspend fun outgoingStatus(localId: Long): MessageStatus = when (simDeLaLigne[localId]) {
            in muette -> MessageStatus.PENDING
            in echec -> MessageStatus.FAILED
            else -> MessageStatus.SENT
        }
    }

    private fun urgence(radio: Radio, expediteur: Expediteur): TriggerEmergencyUseCase {
        val reglages = FakeSettings(
            AppSettings().let { base ->
                base.copy(
                    sending = base.sending.copy(defaultSubId = 11),
                    security = base.security.copy(
                        emergency = EmergencyConfig(enabled = true, includeLocation = false),
                        safetyCall = base.security.safetyCall.copy(
                            contacts = listOf(
                                SafetyCallContact(phoneNumber = CONTACT),
                                SafetyCallContact(phoneNumber = "+33622222222"),
                            ),
                        ),
                    ),
                )
            },
        )
        val simDEnvoi = SimDEnvoi(deuxSims, Dispatchers.Unconfined)
        val sendSms = SendSmsUseCase(
            defaultAppManager = object : com.filestech.sms.domain.sender.DefaultSmsAppChecker {
                override fun isDefault() = true
            },
            sentSmsRecorder = NoopRecorder(),
            sender = expediteur,
            mirror = radio,
            blockedRepo = NeverBlocked(),
            settings = reglages,
            simDEnvoi = simDEnvoi,
        )
        return TriggerEmergencyUseCase(
            envoi = EnvoiDAlerte(sendSms, radio, simDEnvoi, deuxSims, reglages, Dispatchers.Unconfined),
            settings = reglages,
            panicState = object : PanicStateProvider { override val isPanicDecoyActive = false },
            location = object : LocationProvider {
                override suspend fun resolveLocation(): GeoLocation? = null
            },
            textes = FakeSafetyMessageTexts(),
            io = Dispatchers.Unconfined,
        )
    }

    @Test
    fun `sans reseau sur la SIM choisie, l'alerte part par l'autre et le dit`() = runTest {
        val expediteur = Expediteur()

        val resultat = urgence(Radio(echec = setOf(11)), expediteur)()

        assertThat(resultat).isEqualTo(
            TriggerEmergencyUseCase.Result.Triggered(sent = 2, failed = 0, hadLocation = false, parAutreSim = 2),
        )
        assertThat(expediteur.sims).containsExactly(11, 11, 22, 22).inOrder()
    }

    @Test
    fun `un message encore sans verdict compte comme envoye, sans renvoi`() = runTest {
        val expediteur = Expediteur()

        val resultat = urgence(Radio(muette = setOf(11)), expediteur)()

        assertThat(resultat).isEqualTo(
            TriggerEmergencyUseCase.Result.Triggered(sent = 2, failed = 0, hadLocation = false, parAutreSim = 0),
        )
        assertThat(expediteur.sims).containsExactly(11, 11)
    }
}
