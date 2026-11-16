package com.filestech.sms.domain.usecase

import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.emergency.IAmOkMessageProvider
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.security.PanicStateProvider
import com.filestech.sms.domain.settings.AppSettingsSource
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
    // 2026-11-16 (v1.31.0) — l'envoi passe par [EnvoiDAlerte], comme l'alerte qu'il dément.
    private val envoi: EnvoiDAlerte,
    private val settings: AppSettingsSource,
    private val panicState: PanicStateProvider,
    private val iAmOkMessage: IAmOkMessageProvider,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend operator fun invoke(): Result = withContext(io) {
        if (panicState.isPanicDecoyActive) {
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

        val body = iAmOkMessage.body().trim()
        if (body.isBlank()) {
            Timber.w("IAmOkUseCase: i_am_ok_body resource is blank — skipping SMS")
            return@withContext Result.ResetWithoutSms
        }

        Timber.i("IAmOkUseCase: sending I-am-ok SMS to %d contact(s)", contacts.size)
        var invalides = 0
        val cibles = contacts.mapNotNull { contact ->
            val target = contact.takeIf { it.isValid() }?.let { PhoneAddress.of(it.phoneNumber) }
            if (target == null || target.normalized.isEmpty()) {
                invalides++
                null
            } else {
                target
            }
        }
        // 2026-11-16 (v1.31.0) — même chemin que l'alerte ([EnvoiDAlerte]) : si l'alerte est partie par l'autre SIM
        // faute de réseau sur la première, le message rassurant échouerait sur celle-ci, et les proches resteraient
        // sur l'alerte. Sans verdict à l'échéance, il compte comme envoyé, comme avant.
        val bilan = envoi.envoyer(cibles, body)
        val sent = bilan.parties + bilan.sansVerdict
        val failed = invalides + bilan.echouees

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
