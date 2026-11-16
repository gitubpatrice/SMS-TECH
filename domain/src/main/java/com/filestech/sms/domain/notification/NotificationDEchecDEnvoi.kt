package com.filestech.sms.domain.notification

/**
 * 2026-11-16 (v1.31.0) — retirer la notification « échec d'envoi » d'un message.
 *
 * Une alerte qui échoue sur la SIM choisie repart par l'autre (`EnvoiDAlerte`) : la notification posée pour le
 * premier envoi disait alors « échec » à propos d'un proche bel et bien prévenu, avec un bouton Renvoyer qui lui
 * aurait envoyé l'alerte une fois de plus (relecture Claude du 2026-11-16). La bulle en échec reste dans la
 * conversation, elle : c'est l'historique.
 */
interface NotificationDEchecDEnvoi {
    fun retirer(messageId: Long)
}
