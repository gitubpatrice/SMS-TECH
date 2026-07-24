package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.security.PanicStateProvider
import com.filestech.sms.domain.settings.AppSettingsSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * v1.9.0 — Évalue l'état du Safety call deadman et déclenche l'envoi des
 * SMS d'urgence si le timer a expiré.
 *
 * Appelé depuis [com.filestech.sms.system.scheduler.SafetyCallWorker] toutes
 * les 60 minutes. Idempotent : un second appel après trigger réussi est un
 * no-op puisque `enabled` est désormais `false`.
 *
 * **Garde panic-decoy (audit fix CRITICAL)** : si l'app est en session
 * [com.filestech.sms.security.AppLockManager.LockState.PanicDecoy], retourne [Result.PanicSuppressed]
 * sans envoi. Sinon l'agresseur sous contrainte verrait partir les SMS
 * d'urgence devant lui, révélant le réseau de soutien de la victime. Le
 * worker du tick suivant retentera dès que la session decoy est quittée.
 *
 * **Désactivation préemptive (audit fix SEC-3)** : `disableSafetyCall()` est
 * appelé AVANT la boucle d'envoi. Si le process crashe entre 2 sends, le
 * tick worker N+1 ne re-déclenchera pas (enabled=false). L'utilisateur
 * réactive manuellement.
 *
 * **Garde-fous** :
 *  - Refuse si liste de contacts vide.
 *  - Refuse si template = CUSTOM avec message vide.
 *  - Skippe les contacts au numéro invalide (log warn redacté) mais les
 *    autres reçoivent le SMS — un seul mauvais numéro ne bloque pas tout.
 *
 * **Logs (audit fix SEC-1)** : aucun `phoneNumber` n'est loggé en clair.
 *
 * **v1.10.0 — refacto C1** : ex-`SafetyCallTriggerService` (état non
 * pertinent, l'état vit dans [SettingsRepository] déjà `@Singleton`).
 * Migré vers le pattern UseCase dominant du projet avec `operator invoke()`.
 */
class TriggerSafetyCallUseCase @Inject constructor(
    private val sendSms: SendSmsUseCase,
    private val settings: AppSettingsSource,
    private val panicState: PanicStateProvider,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend operator fun invoke(): Result = withContext(io) {
        if (panicState.isPanicDecoyActive) {
            Timber.i("TriggerSafetyCallUseCase: PanicDecoy active, suppressing trigger")
            return@withContext Result.PanicSuppressed
        }

        val current = settings.flow.first().security.safetyCall
        if (!current.enabled) {
            Timber.d("TriggerSafetyCallUseCase: disabled, skipping")
            return@withContext Result.Disabled
        }
        if (!current.isExpired()) {
            Timber.d(
                "TriggerSafetyCallUseCase: armed but not expired (lastActivity=%d, timeout=%dh)",
                current.lastActivityAt, current.timeoutMs / 3_600_000L,
            )
            return@withContext Result.NotExpired
        }
        if (current.contacts.isEmpty()) {
            Timber.w("TriggerSafetyCallUseCase: expired but no contacts configured — disabling")
            disableSafetyCall()
            return@withContext Result.NoContacts
        }
        val body = current.template
            .render(current.timeoutMs, current.customMessage)
            .trim()
        if (body.isBlank()) {
            Timber.w("TriggerSafetyCallUseCase: expired but rendered body is blank — disabling")
            disableSafetyCall()
            return@withContext Result.EmptyBody
        }

        Timber.i(
            "TriggerSafetyCallUseCase: TRIGGER — sending to %d contact(s), template=%s, timeout=%dh",
            current.contacts.size, current.template.name, current.timeoutMs / 3_600_000L,
        )

        disableSafetyCall()

        var sent = 0
        var failed = 0
        current.contacts.forEachIndexed { index, contact ->
            if (!contact.isValid()) {
                Timber.w("TriggerSafetyCallUseCase: skipping invalid contact #%d", index)
                failed++
                return@forEachIndexed
            }
            val target = PhoneAddress.of(contact.phoneNumber)
            if (target.normalized.isEmpty()) {
                Timber.w("TriggerSafetyCallUseCase: skipping unresolvable contact #%d", index)
                failed++
                return@forEachIndexed
            }
            val outcome = sendSms(
                recipients = listOf(target),
                body = body,
                appendSignature = false,
            )
            when (outcome) {
                is Outcome.Success -> sent++
                is Outcome.Failure -> {
                    Timber.w("TriggerSafetyCallUseCase: send failed for contact #%d", index)
                    failed++
                }
            }
        }

        Timber.i(
            "TriggerSafetyCallUseCase: TRIGGER done — sent=%d failed=%d, deadman now disabled",
            sent, failed,
        )
        return@withContext Result.Triggered(sent = sent, failed = failed)
    }

    private suspend fun disableSafetyCall() {
        settings.update { s ->
            s.copy(security = s.security.copy(safetyCall = s.security.safetyCall.copy(enabled = false)))
        }
    }

    sealed interface Result {
        /** SafetyCall désactivé dans Settings (no-op normal). */
        data object Disabled : Result

        /** SafetyCall armé mais pas encore expiré. */
        data object NotExpired : Result

        /** Expiré mais liste de contacts vide (config invalide auto-corrigée). */
        data object NoContacts : Result

        /** Expiré mais body rendu vide (template CUSTOM avec message vide). */
        data object EmptyBody : Result

        /**
         * Session [com.filestech.sms.security.AppLockManager.LockState.PanicDecoy] active — trigger
         * supprimé pour ne pas révéler les contacts d'urgence à l'agresseur.
         * Le worker tick suivant retentera.
         */
        data object PanicSuppressed : Result

        /**
         * Trigger effectué. [sent] = SMS envoyés, [failed] = contacts pour
         * lesquels l'envoi a échoué (numéro invalide, default SMS app,
         * blocklist, échec sender).
         */
        data class Triggered(val sent: Int, val failed: Int) : Result
    }
}
