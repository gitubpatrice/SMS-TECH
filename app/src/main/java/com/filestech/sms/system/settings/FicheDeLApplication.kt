package com.filestech.sms.system.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * v1.28.13 — ouvre la fiche Android de l'application (permissions, notifications, stockage).
 *
 * C'est le seul endroit d'où l'on peut rendre une permission qu'Android ne propose plus : après
 * deux refus, `requestPermissions` répond « refusé » sans rien afficher, et un bouton qui ne
 * passerait que par la boîte de dialogue système ne ferait alors rien du tout.
 *
 * Sert aussi de repli à [com.filestech.sms.system.locale.ouvrirLaLangueDeLApplication].
 */
fun ouvrirLaFicheDeLApplication(contexte: Context) {
    val fiche = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", contexte.packageName, null),
    )
    runCatching { contexte.startActivity(fiche) }
}
