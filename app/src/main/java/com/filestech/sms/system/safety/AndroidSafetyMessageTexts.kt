package com.filestech.sms.system.safety

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.filestech.sms.R
import com.filestech.sms.domain.emergency.EmergencyTemplate
import com.filestech.sms.domain.safety.SafetyMessageTexts
import com.filestech.sms.domain.safetycall.SafetyCallConfig
import com.filestech.sms.domain.safetycall.SafetyCallDuration
import com.filestech.sms.domain.safetycall.SafetyCallTemplate
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.28.12 — L'implémentation Android de [SafetyMessageTexts] : les ressources, et rien d'autre.
 *
 * Pourquoi le contexte d'application suffit
 * ------------------------------------------
 * L'application n'a **pas** de sélecteur de langue à elle : elle ouvre celui d'Android 13+
 * (`res/xml/locales_config.xml` + `system/locale/LangueDeLApplication.kt`), et sous Android 12
 * et antérieurs elle suit le système. Dans les deux cas c'est le SYSTÈME qui applique la locale
 * aux ressources de l'application : `getString` rend donc la bonne langue, y compris depuis un
 * travail de fond — ce qui compte, puisque le Safety call part sans que personne ne regarde.
 *
 * ⚠️ Ce paragraphe affirmait que `settings_language` et `settings_section_locale` étaient des
 * chaînes orphelines, « vérifié le 2026-09-16 ». Elles ont été câblées le lendemain, dans
 * `SettingsScreen`, par le commit qui a ouvert ce sélecteur — le commentaire juste au-dessus du
 * câblage dit lui-même « enfin atteignable ». Deux affirmations contradictoires dans le même
 * dépôt, à un jour d'écart. Corrigé le 2026-09-17.
 *
 * Si un sélecteur interne arrive un jour via `AppCompatDelegate.setApplicationLocales`, il
 * faudra revérifier ce point sous Android 12 et antérieurs, où la locale par application est
 * émulée et n'atteint pas toujours le contexte d'application.
 *
 * Une seule source pour l'aperçu et pour l'envoi
 * ----------------------------------------------
 * `EmergencySetupScreen` montre à l'utilisateur le message qui partira ; `TriggerEmergencyUseCase`
 * l'envoie. Les deux appellent cette classe. C'est délibéré : ce projet a déjà été mordu trois
 * fois par un correctif posé sur un seul de deux chemins jumeaux, et un aperçu qui mentirait sur
 * le contenu d'un SMS d'urgence serait la pire occurrence possible de ce motif.
 */
@Singleton
class AndroidSafetyMessageTexts @Inject constructor(
    @ApplicationContext private val context: Context,
) : SafetyMessageTexts {

    override fun emergencyBody(template: EmergencyTemplate, locationUrl: String?): String {
        // Une position absente devient une mention explicite, jamais un trou : le destinataire
        // doit pouvoir distinguer « je n'ai pas pu donner ma position » de « l'application a
        // planté ».
        val position = locationUrl?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.safety_sms_location_unavailable)
        val modele = when (template) {
            EmergencyTemplate.NEED_HELP -> R.string.safety_sms_emergency_need_help
            EmergencyTemplate.DANGER -> R.string.safety_sms_emergency_danger
            EmergencyTemplate.DISCREET -> R.string.safety_sms_emergency_discreet
        }
        return context.getString(modele, position)
    }

    override fun safetyCallBody(
        template: SafetyCallTemplate,
        timeoutMs: Long,
        customMessage: String,
    ): String {
        val duree = durationLabel(timeoutMs)
        return when (template) {
            // Le message personnalisé est le texte de l'utilisateur : on ne le traduit pas, on
            // le recoupe et on y injecte la durée. Le recoupage n'est pas cosmétique, cf.
            // [SafetyCallTemplate.capCustom].
            SafetyCallTemplate.CUSTOM -> SafetyCallTemplate.injecterDuree(
                SafetyCallTemplate.capCustom(customMessage),
                duree,
            )
            SafetyCallTemplate.CHECK_IN -> context.getString(R.string.safety_sms_check_in, duree)
            SafetyCallTemplate.URGENT -> context.getString(R.string.safety_sms_urgent, duree)
            SafetyCallTemplate.FOLLOW_UP -> context.getString(R.string.safety_sms_follow_up, duree)
        }
    }

    override fun safetyCallRelance(index: Int): String {
        val minutes = SafetyCallTemplate.minutesDeRelance(index).toInt()
        val modele = when {
            index <= 1 -> R.string.safety_sms_relance_first
            // La DERNIÈRE doit s'annoncer comme la dernière : sans cela un contact attend une
            // suite qui ne viendra jamais au lieu d'agir.
            index >= SafetyCallConfig.RELANCE_COUNT -> R.string.safety_sms_relance_last
            else -> R.string.safety_sms_relance_middle
        }
        return context.getString(modele, minutes)
    }

    override fun durationLabel(timeoutMs: Long): String =
        when (val duree = SafetyCallDuration.of(timeoutMs)) {
            SafetyCallDuration.LessThanAnHour ->
                context.getString(R.string.safety_duration_less_than_hour)
            is SafetyCallDuration.Hours -> context.resources.getQuantityString(
                R.plurals.safety_duration_hours,
                duree.count.toInt(),
                duree.count.toInt(),
            )
            is SafetyCallDuration.Days -> context.resources.getQuantityString(
                R.plurals.safety_duration_days,
                duree.count.toInt(),
                duree.count.toInt(),
            )
        }
}

/**
 * v1.28.12 — la MEME source pour les ecrans d'apercu que pour l'envoi.
 *
 * `EmergencySetupScreen`, `EmergencyScreen` et `SafetyCallSetupScreen` montrent a l'utilisateur
 * le message qui partira. Ils passent donc par [AndroidSafetyMessageTexts], exactement comme
 * `TriggerEmergencyUseCase` et `TriggerSafetyCallUseCase`.
 *
 * Pourquoi pas une injection Hilt dans le ViewModel : la classe est sans etat et ne tient qu'un
 * contexte ; la construire ici garantit que l'apercu et l'envoi ne peuvent pas diverger, ce qui
 * est precisement le risque a fermer. Un apercu qui mentirait sur le contenu d'un SMS d'urgence
 * serait la pire occurrence du motif « correctif pose sur un seul des chemins jumeaux », qui a
 * deja mordu ce projet trois fois.
 */
@Composable
fun rememberSafetyMessageTexts(): SafetyMessageTexts {
    val contexte = LocalContext.current
    return remember(contexte) { AndroidSafetyMessageTexts(contexte) }
}
