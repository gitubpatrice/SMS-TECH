package com.filestech.sms.domain.usecase

import android.os.SystemClock
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.location.LocationProvider
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.security.PanicStateProvider
import com.filestech.sms.domain.settings.AppSettingsSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * v1.10.0 — Mode urgence : envoie un SMS d'urgence aux contacts d'urgence
 * (les mêmes que le Safety call) contenant un message + la géoloc actuelle
 * de l'utilisateur sous forme d'URL Maps cliquable.
 *
 * Déclenché par l'utilisateur via le bouton hold 3s dans l'écran Mode
 * urgence. Distinct du Safety call qui est un déclenchement passif sur
 * timer d'inactivité.
 *
 * **Garde panic-decoy (CRITICAL)** : refuse le trigger si l'app est en
 * session [com.filestech.sms.security.AppLockManager.LockState.PanicDecoy]. Sinon un agresseur ayant
 * forcé l'ouverture en mode decoy verrait partir les SMS aux contacts
 * d'urgence devant lui — révélation du réseau de soutien.
 *
 * **Garde-fous** :
 *  - Refuse si [com.filestech.sms.domain.settings.AppSettings] :
 *    `security.emergency.enabled == false` (defense in depth ; l'UI cache
 *    déjà le bouton).
 *  - Refuse si liste de contacts vide (l'UI grise déjà le bouton, mais
 *    on re-check au cas où la config change pendant le hold 3s).
 *  - Skippe les contacts au numéro invalide (log redacté), mais les
 *    autres reçoivent le SMS — un seul mauvais numéro ne bloque pas tout.
 *
 * **Géoloc** : si `security.emergency.includeLocation == true`, on tente
 * un fix via [com.filestech.sms.data.location.LocationResolver] (timeout 8s, fallback `lastKnownLocation`
 * < 5 min). Si null → SMS sans coord avec mention explicite "(position
 * non disponible)". L'UI affiche un snackbar warning.
 *
 * **Logs** : aucun `phoneNumber` ni `lat/lon` n'est loggé en clair —
 * identifiés par index ou par hash courte.
 */
class TriggerEmergencyUseCase @Inject constructor(
    private val sendSms: SendSmsUseCase,
    private val settings: AppSettingsSource,
    private val panicState: PanicStateProvider,
    private val location: LocationProvider,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend operator fun invoke(): Result = withContext(io) {
        if (panicState.isPanicDecoyActive) {
            Timber.i("TriggerEmergencyUseCase: PanicDecoy active, suppressing trigger")
            return@withContext Result.PanicSuppressed
        }

        val snapshot = settings.flow.first()
        val emergency = snapshot.security.emergency
        if (!emergency.enabled) {
            Timber.w("TriggerEmergencyUseCase: emergency disabled but trigger called — UI desync?")
            return@withContext Result.Disabled
        }
        val contacts = snapshot.security.safetyCall.contacts
        if (contacts.isEmpty()) {
            Timber.w("TriggerEmergencyUseCase: no contacts configured — aborting")
            return@withContext Result.NoContacts
        }

        val locationUrl: String? = if (emergency.includeLocation) {
            runCatching { location.resolveLocation() }
                .onFailure { Timber.w(it, "TriggerEmergencyUseCase: location lookup threw") }
                .getOrNull()
                ?.let { loc ->
                    // Format minimaliste : 5 décimales (~ 1 mètre de précision)
                    // suffisent pour le secours et restent compactes pour
                    // tenir dans un segment SMS.
                    "https://maps.google.com/?q=%.5f,%.5f".format(loc.latitude, loc.longitude)
                }
        } else {
            null
        }
        val hadLocation = locationUrl != null

        val body = emergency.template.renderBody(locationUrl).trim()
        if (body.isBlank()) {
            Timber.w("TriggerEmergencyUseCase: rendered body is blank — aborting")
            return@withContext Result.EmptyBody
        }

        Timber.i(
            "TriggerEmergencyUseCase: TRIGGER — sending to %d contact(s), template=%s, hadLocation=%s",
            contacts.size, emergency.template.name, hadLocation,
        )

        var sent = 0
        var failed = 0
        contacts.forEachIndexed { index, contact ->
            if (!contact.isValid()) {
                Timber.w("TriggerEmergencyUseCase: skipping invalid contact #%d", index)
                failed++
                return@forEachIndexed
            }
            val target = PhoneAddress.of(contact.phoneNumber)
            if (target.normalized.isEmpty()) {
                Timber.w("TriggerEmergencyUseCase: skipping unresolvable contact #%d", index)
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
                    Timber.w("TriggerEmergencyUseCase: send failed for contact #%d", index)
                    failed++
                }
            }
        }

        // Pose le timestamp anti-spam UI (60s). N'auto-disable PAS la feature
        // (urgence répétable, contrairement au Safety call qui est unique).
        // v1.10.0 audit S2 — couple wall+mono pour bloquer un bypass via
        // avance de la wall-clock OS par un attaquant root.
        val triggeredAtWall = System.currentTimeMillis()
        val triggeredAtMono = SystemClock.elapsedRealtime()
        settings.update { s ->
            s.copy(
                security = s.security.copy(
                    emergency = s.security.emergency.copy(
                        lastTriggeredAt = triggeredAtWall,
                        monotonicLastTriggeredAt = triggeredAtMono,
                    ),
                ),
            )
        }

        Timber.i(
            "TriggerEmergencyUseCase: TRIGGER done — sent=%d failed=%d hadLocation=%s",
            sent, failed, hadLocation,
        )
        return@withContext Result.Triggered(
            sent = sent,
            failed = failed,
            hadLocation = hadLocation,
        )
    }

    sealed interface Result {
        /** Mode urgence désactivé dans Settings (config desync UI). */
        data object Disabled : Result

        /** Liste de contacts vide (l'UI aurait dû griser le bouton). */
        data object NoContacts : Result

        /** Body rendu vide (template corrompu). */
        data object EmptyBody : Result

        /**
         * Session [com.filestech.sms.security.AppLockManager.LockState.PanicDecoy] active — trigger
         * supprimé pour ne pas révéler les contacts d'urgence à l'agresseur.
         */
        data object PanicSuppressed : Result

        /**
         * Trigger effectué. [sent] = SMS envoyés, [failed] = contacts dont
         * l'envoi a échoué, [hadLocation] = si la géoloc a pu être incluse
         * dans le SMS (false → SMS envoyé avec mention "position non
         * disponible", l'UI affiche un snackbar warning).
         */
        data class Triggered(
            val sent: Int,
            val failed: Int,
            val hadLocation: Boolean,
        ) : Result
    }
}
