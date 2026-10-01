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
 * Sert aussi de repli à [com.filestech.sms.system.locale.ouvrirLaLangueDeLApplication] et à
 * [ouvrirLesNotificationsDeLApplication].
 */
fun ouvrirLaFicheDeLApplication(contexte: Context) {
    val fiche = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", contexte.packageName, null),
    )
    runCatching { contexte.startActivity(fiche) }
}

/**
 * v1.28.13 — ouvre la page Android des notifications de l'application (API 26+, donc toujours
 * disponible au `minSdk` 26). C'est là que se rendent `POST_NOTIFICATIONS` refusée deux fois, les
 * notifications coupées pour l'application, et un canal coupé. Repli : la fiche de l'application,
 * pour les constructeurs qui n'exposeraient pas cette page.
 */
fun ouvrirLesNotificationsDeLApplication(contexte: Context) {
    val notifications = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, contexte.packageName)
    runCatching { contexte.startActivity(notifications) }
        .onFailure { ouvrirLaFicheDeLApplication(contexte) }
}
