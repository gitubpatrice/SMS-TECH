package com.filestech.sms.domain.usecase

import android.content.Context
import com.filestech.sms.R
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.security.AppLockManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * v1.14.0 — Kill-switch « Je vais bien » : invoqué quand l'user veut signaler
 * a posteriori que le déclenchement urgence était une fausse alerte. Deux
 * effets :
 *
 *  1. Reset du couple `lastTriggeredAt` + `monotonicLastTriggeredAt` à `0L`
 *     dans `AppSettings.security.emergency`. Cela :
 *       - masque le chip "Je vais bien" sur [ConversationsScreen] (visible
 *         seulement pendant la fenêtre 30 min post-trigger).
 *       - reset le cooldown anti-spam : si une VRAIE urgence se produit
 *         dans la minute, l'user peut re-déclencher le bouton hold-3s sans
 *         attendre.
 *
 *  2. Si `security.sendIAmOkSmsOnReset == true` (default `true`) : envoie un
 *     SMS court "Je vais bien, fausse alerte" à chaque contact SafetyCall.
 *     L'opt-in permet à l'user de désactiver l'envoi automatique s'il
 *     préfère gérer manuellement (texte custom, choix des destinataires).
 *
 * **Garde panic-decoy (CRITICAL)** : refuse si `LockState.PanicDecoy` actif.
 * Un agresseur ne doit pas pouvoir « reset » le déclenchement urgence pour
 * effacer la trace UI (anti-tampering en situation de coercition).
 *
 * **Idempotent** : si appelé avec `lastTriggeredAt == 0L` (jamais déclenché),
 * ne fait rien sauf retourner [Result.NothingToReset]. Pas d'envoi SMS dans
 * ce cas.
 */
class IAmOkUseCase @Inject constructor(
    private val sendSms: SendSmsUseCase,
    private val settings: SettingsRepository,
    private val appLock: AppLockManager,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend operator fun invoke(): Result = withContext(io) {
        if (appLock.state.value is AppLockManager.LockState.PanicDecoy) {
            Timber.i("IAmOkUseCase: PanicDecoy active, suppressing reset")
            return@withContext Result.PanicSuppressed
        }

        val snapshot = settings.flow.first()
        val emergency = snapshot.security.emergency
        if (emergency.lastTriggeredAt == 0L) {
            return@withContext Result.NothingToReset
        }

        // 1. Reset du timestamp (cooldown + chip UI).
        settings.update { s ->
            s.copy(
                security = s.security.copy(
                    emergency = s.security.emergency.copy(
                        lastTriggeredAt = 0L,
                        monotonicLastTriggeredAt = 0L,
                    ),
                ),
            )
        }

        // 2. SMS "Je vais bien" si opt-in (default true).
        if (!snapshot.security.sendIAmOkSmsOnReset) {
            Timber.i("IAmOkUseCase: reset done, sendIAmOkSmsOnReset=false, no SMS sent")
            return@withContext Result.ResetWithoutSms
        }

        val contacts = snapshot.security.safetyCall.contacts
        if (contacts.isEmpty()) {
            Timber.i("IAmOkUseCase: reset done, no contacts, no SMS sent")
            return@withContext Result.ResetWithoutSms
        }

        val body = context.getString(R.string.emergency_i_am_ok_body).trim()
        if (body.isBlank()) {
            Timber.w("IAmOkUseCase: i_am_ok_body resource is blank — skipping SMS")
            return@withContext Result.ResetWithoutSms
        }

        Timber.i("IAmOkUseCase: sending I-am-ok SMS to %d contact(s)", contacts.size)
        var sent = 0
        var failed = 0
        contacts.forEachIndexed { index, contact ->
            if (!contact.isValid()) {
                failed++
                return@forEachIndexed
            }
            val target = PhoneAddress.of(contact.phoneNumber)
            if (target.normalized.isEmpty()) {
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
                    Timber.w("IAmOkUseCase: send failed for contact #%d", index)
                    failed++
                }
            }
        }

        Timber.i("IAmOkUseCase: I-am-ok done — sent=%d failed=%d", sent, failed)
        return@withContext Result.ResetAndSmsSent(sent = sent, failed = failed)
    }

    sealed interface Result {
        /** Aucun trigger urgence à reset (chip déjà absent côté UI). */
        data object NothingToReset : Result

        /** Reset effectué, pas de SMS envoyé (opt-in OFF ou pas de contacts). */
        data object ResetWithoutSms : Result

        /** Reset effectué + SMS "Je vais bien" envoyé aux contacts. */
        data class ResetAndSmsSent(val sent: Int, val failed: Int) : Result

        /** PanicDecoy actif — reset refusé (anti-tampering coercition). */
        data object PanicSuppressed : Result
    }
}
