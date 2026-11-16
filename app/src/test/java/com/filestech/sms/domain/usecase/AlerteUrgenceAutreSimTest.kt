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
import kotlinx.coroutines.flow.first
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
        /** Ce qui se passe pendant que l'alerte attend le verdict (un « Je vais bien », par exemple). */
        private val pendantLAttente: suspend () -> Unit = {},
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

        override suspend fun outgoingStatus(localId: Long): MessageStatus {
            pendantLAttente()
            return when (simDeLaLigne[localId]) {
                in muette -> MessageStatus.PENDING
                in echec -> MessageStatus.FAILED
                else -> MessageStatus.SENT
            }
        }
    }

    /**
     * Relecture Claude du 2026-11-16 : un « Je vais bien » dit pendant que l'alerte attend son verdict arrête les
     * renvois ; sans cela, l'alerte repartie par l'autre SIM arrivait APRÈS le message rassurant.
     */
    @Test
    fun `un « Je vais bien » pendant l'attente arrete les renvois de l'alerte`() = runTest {
        val expediteur = Expediteur()
        val reglages = reglagesUrgence()
        val jeVaisBien: suspend () -> Unit = {
            reglages.update { s ->
                s.copy(security = s.security.copy(emergency = s.security.emergency.copy(lastTriggeredAt = 0L)))
            }
        }

        val resultat = urgence(Radio(echec = setOf(11), pendantLAttente = jeVaisBien), expediteur, reglages)()

        assertThat(expediteur.sims).containsExactly(11, 11)
        assertThat((resultat as TriggerEmergencyUseCase.Result.Triggered).parAutreSim).isEqualTo(0)
    }

    /**
     * Relecture Claude du 2026-11-16 : le délai anti-répétition est posé AVANT l'envoi. Posé après, pendant toute
     * l'attente du verdict, un écran rouvert se disait « prêt » et un second appui envoyait l'alerte deux fois.
     */
    @Test
    fun `le delai anti-repetition est deja pose pendant l'attente du verdict`() = runTest {
        val reglages = reglagesUrgence()
        val pendantLEnvoi = mutableListOf<Long>()
        val radio = Radio(
            pendantLAttente = { pendantLEnvoi += reglages.flow.first().security.emergency.lastTriggeredAt },
        )

        urgence(radio, Expediteur(), reglages)()

        assertThat(pendantLEnvoi).isNotEmpty()
        assertThat(pendantLEnvoi.all { it > 0L }).isTrue()
    }

    private fun urgence(
        radio: Radio,
        expediteur: Expediteur,
        reglages: FakeSettings = reglagesUrgence(),
    ): TriggerEmergencyUseCase {
        val simDEnvoi = SimDEnvoi(deuxSims, Dispatchers.Unconfined)
        return construire(radio, expediteur, reglages, simDEnvoi)
    }

    private fun reglagesUrgence(): FakeSettings = FakeSettings(
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

    /**
     * Audit pré-release du 2026-11-16 : un effacement de panique pendant l'attente du verdict arrête les renvois de
     * « Je vais bien » ; sans cela, le renvoi par l'autre SIM réécrivait, après l'effacement, le numéro du proche.
     */
    @Test
    fun `un effacement de panique pendant l'attente arrete les renvois de « Je vais bien »`() = runTest {
        val expediteur = Expediteur()
        val reglages = reglagesUrgence()
        reglages.update { s ->
            s.copy(security = s.security.copy(emergency = s.security.emergency.copy(lastTriggeredAt = 1L)))
        }
        val effacement: suspend () -> Unit = { reglages.update { AppSettings() } }
        val radio = Radio(echec = setOf(11), pendantLAttente = effacement)
        val jeVaisBien = IAmOkUseCase(
            envoi = envoiDAlerte(radio, expediteur, reglages, SimDEnvoi(deuxSims, Dispatchers.Unconfined)),
            settings = reglages,
            panicState = object : PanicStateProvider { override val isPanicDecoyActive = false },
            iAmOkMessage = object : com.filestech.sms.domain.emergency.IAmOkMessageProvider {
                override fun body() = "Je vais bien"
            },
            io = Dispatchers.Unconfined,
        )

        jeVaisBien()

        // Les deux contacts une fois par la SIM 11, en échec ; aucun renvoi par la 22 après l'effacement.
        assertThat(expediteur.sims).containsExactly(11, 11)
    }

    private fun envoiDAlerte(
        radio: Radio,
        expediteur: Expediteur,
        reglages: FakeSettings,
        simDEnvoi: SimDEnvoi,
    ): EnvoiDAlerte = EnvoiDAlerte(
        sendSms = SendSmsUseCase(
            defaultAppManager = object : com.filestech.sms.domain.sender.DefaultSmsAppChecker {
                override fun isDefault() = true
            },
            sentSmsRecorder = NoopRecorder(),
            sender = expediteur,
            mirror = radio,
            blockedRepo = NeverBlocked(),
            settings = reglages,
            simDEnvoi = simDEnvoi,
        ),
        mirror = radio,
        simDEnvoi = simDEnvoi,
        sims = deuxSims,
        settings = reglages,
        notificationsDEchec = object : com.filestech.sms.domain.notification.NotificationDEchecDEnvoi {
            override fun retirer(messageId: Long) = Unit
        },
        io = Dispatchers.Unconfined,
    )

    private fun construire(
        radio: Radio,
        expediteur: Expediteur,
        reglages: FakeSettings,
        simDEnvoi: SimDEnvoi,
    ): TriggerEmergencyUseCase {
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
            envoi = EnvoiDAlerte(
                sendSms = sendSms,
                mirror = radio,
                simDEnvoi = simDEnvoi,
                sims = deuxSims,
                settings = reglages,
                notificationsDEchec = object : com.filestech.sms.domain.notification.NotificationDEchecDEnvoi {
                    override fun retirer(messageId: Long) = Unit
                },
                io = Dispatchers.Unconfined,
            ),
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
