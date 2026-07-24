package com.filestech.sms.domain.sender

/**
 * Port domaine : enregistre un SMS **sortant** dans la boîte « Envoyés » du fournisseur système
 * (`content://sms`), pour qu'il apparaisse dans les autres applications SMS.
 *
 * [com.filestech.sms.domain.usecase.SendSmsUseCase] en dépend. L'implémentation
 * [com.filestech.sms.data.sms.TelephonyReader] renvoie l'URI système sous forme de **chaîne** —
 * `domain/` reste sans import `android.net.Uri`.
 */
interface SentSmsRecorder {

    /**
     * Insère un SMS envoyé et renvoie l'URI système (`content://sms/…`) sous forme de chaîne, ou
     * `null` si l'insertion a échoué. La chaîne est mémorisée sur la ligne miroir Room.
     */
    fun insertSentSms(
        address: String,
        body: String,
        date: Long,
        threadId: Long? = null,
        subId: Int? = null,
    ): String?
}
