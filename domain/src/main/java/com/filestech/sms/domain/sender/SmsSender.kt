package com.filestech.sms.domain.sender

import com.filestech.sms.core.result.Outcome

/**
 * Port domaine de l'envoi d'un SMS via la pile téléphonie Android.
 *
 * Les use-cases d'envoi ([com.filestech.sms.domain.usecase.SendSmsUseCase],
 * [com.filestech.sms.domain.usecase.RetrySendUseCase]) dépendent de cette abstraction et non de la
 * couche `data` : c'est ce qui rend `domain/` indépendant de `data/` (Étage 2.1, inversion de
 * dépendance). L'implémentation [com.filestech.sms.data.sms.SmsSenderImpl] enveloppe `SmsManager`
 * et rattache l'id Room aux `PendingIntent` de suivi.
 */
interface SmsSender {

    /**
     * Découpe [text] en parts multipartes et l'envoie à [destination] via la pile téléphonie.
     *
     * @param localMessageId id Room du message miroir, encodé dans les `PendingIntent` de suivi
     *   pour que les receivers (`SmsSentReceiver` / `SmsDeliveredReceiver`) puissent mettre à jour
     *   la ligne à `SENT` / `DELIVERED` / `FAILED`.
     * @param destination numéro brut du destinataire (l'impl le normalise en E.164 pour le fil).
     * @param subId `subscriptionId` de la SIM à utiliser (multi-SIM) ; `null` = SIM par défaut.
     * @param requestDeliveryReport demande un accusé de réception si `true`.
     * @param attempt v1.28.3 (F23) — numéro de la tentative, `0` pour un premier envoi, incrémenté
     *   par chaque relance explicite. Il est encodé dans les `PendingIntent` **et dans leur
     *   `requestCode`** : sans lui, deux tentatives du même message partageaient un seul
     *   `PendingIntent` (`filterEquals` ignore les extras, et `FLAG_UPDATE_CURRENT` réécrivait
     *   ceux du premier), et leurs accusés étaient indiscernables.
     * @return [Outcome.Success] si la remise à `SmsManager` a réussi, [Outcome.Failure] sinon.
     */
    fun send(
        localMessageId: Long,
        destination: String,
        text: String,
        subId: Int? = null,
        requestDeliveryReport: Boolean = false,
        attempt: Int = 0,
    ): Outcome<Unit>
}
