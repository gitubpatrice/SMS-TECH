package com.filestech.sms.system.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.filestech.sms.system.scheduler.ArriveesDuProfilPrincipalWorker

/**
 * 2026-11-27 (issue #98) — `SMS_RECEIVED`, que le système envoie à TOUS les utilisateurs en marche une fois le SMS
 * remis au profil principal. Dans un profil secondaire, lance la passe qui l'importe et le notifie, cf.
 * [ArriveesDuProfilPrincipal]. Dans le profil principal, rien : il a reçu le SMS lui-même ([SmsDeliverReceiver]).
 *
 * Le message n'est PAS lu dans l'intention : seul le stockage partagé fait foi, et il donne au message son
 * identifiant, sa conversation et son dédoublonnage. Le travail part dans WorkManager : la diffusion est de premier
 * plan (dix secondes avant une ANR), et une passe peut attendre la fin d'une synchronisation en cours.
 */
class SmsRecuAuProfilPrincipalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        ArriveesDuProfilPrincipalWorker.apresUnSms(context)
    }
}
