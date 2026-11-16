package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.sender.SmsSender
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * 2026-11-16 (v1.31.0, relecture GPT) — **un créneau repris pendant l'envoi ne se conclut plus.**
 *
 * La reprise d'un bail expiré efface `claimedAt` sans toucher à `claimId`. Entre elle et la réservation du repreneur,
 * un worker revenu tardivement passait encore la vérification de propriété de la conclusion (`claimId` et génération
 * seulement) : il levait un bail qui n'était plus le sien et, sur le dernier créneau, désarmait la protection. La
 * conclusion suit désormais la règle de [TriggerSafetyCallUseCase] `renewClaim` : bail levé = créneau perdu.
 */
class SafetyCallBailReprisTest {

    @Test
    fun `un bail leve par une reprise pendant l'envoi rend la main sans conclure`() = runTest {
        val settings = expiredSettings()
        // Le SMS part ; pendant ce temps, un autre worker juge le bail abandonné et le reprend.
        val repris = object : SmsSender {
            override fun send(
                localMessageId: Long,
                destination: String,
                text: String,
                subId: Int?,
                requestDeliveryReport: Boolean,
                attempt: Int,
            ): Outcome<Unit> {
                runBlocking {
                    settings.update { s ->
                        s.copy(security = s.security.copy(safetyCall = s.security.safetyCall.copy(claimedAt = 0L)))
                    }
                }
                return Outcome.Success(Unit)
            }
        }

        val resultat = useCase(settings, repris).invoke()

        assertThat(resultat).isEqualTo(TriggerSafetyCallUseCase.Result.Superseded)
        assertThat(settings.safetyCall.enabled).isTrue()
    }
}
