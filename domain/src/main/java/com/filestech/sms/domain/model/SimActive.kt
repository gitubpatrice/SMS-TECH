package com.filestech.sms.domain.model

/**
 * 2026-11-10 (choix de la SIM d'envoi, v1.30.1) — une carte SIM active du téléphone.
 *
 * @property subId l'identifiant d'abonnement d'Android (`SubscriptionInfo.subscriptionId`). C'est lui que
 *   reçoit `SmsManager`, et lui que porte chaque message (`messages.sub_id`).
 * @property emplacement l'emplacement physique, à partir de 0 : « SIM 1 » est l'emplacement 0.
 * @property nom le nom donné à la SIM dans les réglages d'Android, sinon celui de l'opérateur ; `null` si
 *   Android n'en donne aucun.
 */
data class SimActive(val subId: Int, val emplacement: Int, val nom: String?)
