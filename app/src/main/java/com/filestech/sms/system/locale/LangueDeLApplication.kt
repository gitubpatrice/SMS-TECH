package com.filestech.sms.system.locale

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi

/**
 * v1.28.12 — ouvre l'écran Android « Langue » de l'application.
 *
 * Pourquoi cette entrée existe
 * -----------------------------
 * Le sélecteur de langue par application arrive avec `res/xml/locales_config.xml`, mais il vit
 * dans les réglages d'Android — où personne ne va le chercher. Déclarer la liste des langues sans
 * mener à l'endroit qui les propose, c'est n'offrir la fonctionnalité qu'à moitié.
 *
 * Les chaînes `settings_section_locale` et `settings_language` existaient d'ailleurs déjà dans
 * les trois langues et n'étaient branchées **nulle part** : du texte traduit que personne ne
 * pouvait lire.
 *
 * Le repli n'est pas décoratif
 * -----------------------------
 * `ACTION_APP_LOCALE_SETTINGS` existe depuis Android 13, mais tous les constructeurs n'exposent
 * pas cette page. Sans repli, le tap ne ferait **rien** — ce qui est pire qu'une entrée absente,
 * parce que l'utilisateur en conclut que la fonctionnalité est cassée. On ouvre alors la fiche de
 * l'application, d'où la langue reste atteignable en une tape de plus.
 *
 * L'appel est gardé par `SDK_INT >= TIRAMISU` côté appelant : en dessous, la page n'existe pas.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun ouvrirLaLangueDeLApplication(contexte: Context) {
    val paquet = Uri.fromParts("package", contexte.packageName, null)
    val langue = Intent(Settings.ACTION_APP_LOCALE_SETTINGS, paquet)
    val ficheDeLApplication = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, paquet)
    runCatching { contexte.startActivity(langue) }
        .onFailure { runCatching { contexte.startActivity(ficheDeLApplication) } }
}
