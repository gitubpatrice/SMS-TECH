package com.filestech.sms.domain.mms

import com.filestech.sms.core.result.Outcome
import java.io.File

/**
 * Port domaine : encode et dispatche un MMS via la pile télésphonie (`SmsManager`).
 *
 * Les use-cases [com.filestech.sms.domain.usecase.SendMediaMmsUseCase] et
 * [com.filestech.sms.domain.usecase.SendVoiceMmsUseCase] en dépendent. L'implémentation
 * [com.filestech.sms.data.mms.MmsSender] encode le PDU (`m-send.req`), le partage via
 * `FileProvider` et câble le `PendingIntent` de suivi — détails Android hors du port, qui ne
 * manipule que [File] (JDK) + [MmsAttachment] (domaine).
 */
interface MmsDispatcher {

    /**
     * Encode et dispatche un MMS vocal ([audioFile]). Retourne dès que `SmsManager` prend la main ;
     * le statut SENT/FAILED final arrive de façon asynchrone via le receiver système.
     */
    suspend fun sendVoiceMms(
        localMessageId: Long,
        recipients: List<String>,
        audioFile: File,
        mimeType: String,
        subId: Int? = null,
        requestDeliveryReport: Boolean = false,
    ): Outcome<Unit>

    /** Encode et dispatche un MMS multimédia ([attachments] + [textBody] optionnel). */
    suspend fun sendMediaMms(
        localMessageId: Long,
        recipients: List<String>,
        attachments: List<MmsAttachment>,
        textBody: String? = null,
        subId: Int? = null,
        requestDeliveryReport: Boolean = false,
    ): Outcome<Unit>
}
