package com.filestech.sms.system.service

import android.os.Build

/**
 * v1.3.10 — Détecte les ROMs Android qui tuent agressivement les apps en arrière-plan
 * (Xiaomi MIUI / HyperOS, Huawei EMUI / HarmonyOS, Honor MagicOS, OnePlus / Oppo ColorOS,
 * Realme realmeUI, Vivo / iQOO FunTouch / OriginOS, Meizu Flyme, ASUS Zen UI). Sur ces
 * ROMs, SMS Tech (sideloadée via F-Droid ou GitHub APK) est silencieusement killée en
 * background — réception MMS impossible, notifications jamais posées — sauf si le user
 * active manuellement les 4 réglages OEM (auto-start, batterie, notifs, lock récents) OU
 * si SMS Tech tourne un foreground service permanent
 * ([com.filestech.sms.system.service.KeepAliveService]).
 *
 * Cette classe expose [isAggressiveOem] pour que [com.filestech.sms.MainApplication] +
 * [com.filestech.sms.ui.screens.settings.SettingsViewModel] puissent **suggérer
 * automatiquement** le toggle "Mode résistant" sur ces ROMs, avec un dialog éducatif
 * au premier lancement plutôt qu'attendre que l'utilisateur galère et perde des SMS.
 *
 * **Détection** : basée sur `Build.MANUFACTURER` + `Build.BRAND` (insensible à la casse).
 * Pas de réflexion sur des propriétés cachées (`ro.miui.ui.version.name` etc.) — la valeur
 * `MANUFACTURER` est suffisamment stable et publique pour ce besoin éditorial.
 *
 * **Pas un blocage** : c'est juste une suggestion. L'utilisateur reste libre de désactiver
 * le toggle même sur Xiaomi (au risque de perdre des SMS), ou de l'activer manuellement
 * sur un Pixel pour des raisons spécifiques.
 *
 * Liste maintenue **conservatrice** : on préfère exclure une ROM agressive (faux négatif :
 * user devra activer manuellement) plutôt que d'imposer une notif persistante à un user
 * sur ROM propre (faux positif : pollution shade pour rien).
 */
object OemRomDetector {

    /**
     * `true` si la ROM est connue pour tuer agressivement les apps SMS tierces en
     * background. La suggestion d'activer `KeepAliveService` doit alors être faite.
     */
    val isAggressiveOem: Boolean by lazy {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        AGGRESSIVE_OEMS.any { it in manufacturer || it in brand }
    }

    /**
     * Identifiant lisible de la ROM détectée, pour affichage dans le dialog éducatif
     * ("Détecté : Xiaomi MIUI") et la documentation utilisateur. Retourne `null` si
     * la ROM n'est pas dans la liste agressive (= aucun dialog à afficher).
     */
    val detectedRomLabel: String? by lazy {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        when {
            "xiaomi" in manufacturer || "redmi" in brand || "poco" in brand -> "Xiaomi (MIUI / HyperOS)"
            "huawei" in manufacturer || "huawei" in brand -> "Huawei (EMUI / HarmonyOS)"
            "honor" in manufacturer || "honor" in brand -> "Honor (MagicOS)"
            "oppo" in manufacturer || "oppo" in brand -> "Oppo (ColorOS)"
            "realme" in manufacturer || "realme" in brand -> "Realme (realmeUI)"
            "oneplus" in manufacturer || "oneplus" in brand -> "OnePlus (OxygenOS / ColorOS)"
            "vivo" in manufacturer || "vivo" in brand -> "Vivo (FunTouch OS)"
            "iqoo" in manufacturer || "iqoo" in brand -> "iQOO (OriginOS)"
            "meizu" in manufacturer || "meizu" in brand -> "Meizu (Flyme)"
            "asus" in manufacturer || "asus" in brand -> "ASUS (Zen UI)"
            else -> null
        }
    }

    /**
     * Liste insensible à la casse des fabricants / marques connus pour avoir une couche
     * de gestion mémoire agressive sur leurs ROMs. **Tenue à jour à mesure que les ROMs
     * changent de comportement** (ex: OnePlus avant ColorOS = OxygenOS propre, après =
     * agressif comme Oppo).
     *
     * Sources : tests Patrice 2026-05 + retours utilisateurs + base de données dontkillmyapp.com.
     */
    private val AGGRESSIVE_OEMS: List<String> = listOf(
        "xiaomi", "redmi", "poco",
        "huawei", "honor",
        "oppo", "realme", "oneplus",
        "vivo", "iqoo",
        "meizu", "asus",
    )
}
