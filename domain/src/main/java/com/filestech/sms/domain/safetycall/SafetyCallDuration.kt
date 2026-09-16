package com.filestech.sms.domain.safetycall

import java.util.concurrent.TimeUnit

/**
 * v1.28.12 — L'ARRONDI d'une duree de Safety Call, sans un mot de langue.
 *
 * `SafetyCallTemplate.formatDuration` rendait « 24 heures » ou « 2 jours » : la regle d'arrondi
 * et le francais y etaient noues ensemble, et le francais partait donc dans le SMS de tout le
 * monde. La regle reste ici — elle se teste sans Android et sans locale — et le mot revient a
 * [com.filestech.sms.domain.safety.SafetyMessageTexts.durationLabel], donc aux ressources.
 *
 * La regle, inchangee depuis la v1.9.0 :
 *  - moins d'une heure pleine      -> [LessThanAnHour] ;
 *  - multiple entier de 24 heures  -> [Days] (24 h se dit « 1 jour », pas « 24 heures ») ;
 *  - sinon                         -> [Hours], arrondi a l'heure inferieure.
 */
sealed interface SafetyCallDuration {

    /** Moins d'une heure pleine. Aucun nombre : l'annoncer serait plus precis que la mesure. */
    data object LessThanAnHour : SafetyCallDuration

    data class Hours(val count: Long) : SafetyCallDuration

    data class Days(val count: Long) : SafetyCallDuration

    companion object {
        fun of(timeoutMs: Long): SafetyCallDuration {
            val hours = TimeUnit.MILLISECONDS.toHours(timeoutMs)
            if (hours <= 0L) return LessThanAnHour
            if (hours % 24L == 0L) return Days(hours / 24L)
            return Hours(hours)
        }
    }
}
