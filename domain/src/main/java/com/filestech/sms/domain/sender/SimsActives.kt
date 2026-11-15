package com.filestech.sms.domain.sender

import com.filestech.sms.domain.model.SimActive

/**
 * 2026-11-10 (choix de la SIM d'envoi, v1.30.1) — les SIM du téléphone, lues chez Android.
 *
 * Les deux lectures passent par un appel binder (`SubscriptionManager`) : jamais sur le thread principal,
 * qui peut geler sur certains Samsung (cf. `SimLabeler`).
 */
interface SimsActives {

    /**
     * Les SIM actives, par emplacement. **Vide quand on ne peut pas savoir** : permission Téléphone refusée,
     * appareil sans téléphonie, erreur d'Android. Vide ne veut donc pas dire « aucune SIM ».
     */
    fun lister(): List<SimActive>

    /** La SIM qu'Android utilise pour les SMS, ou `null` (« toujours demander », ou inconnue). */
    fun simSmsDAndroid(): Int?
}
