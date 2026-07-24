package com.filestech.sms.domain.mms

import java.io.File

/**
 * Port domaine : promeut une pièce jointe MMS sortante depuis le cache volatil de staging vers le
 * stockage durable, juste avant que le message soit committé en base et dispatché.
 *
 * Les use-cases d'envoi MMS ([com.filestech.sms.domain.usecase.SendMediaMmsUseCase],
 * [com.filestech.sms.domain.usecase.SendVoiceMmsUseCase]) dépendent de ce port ; l'implémentation
 * [com.filestech.sms.data.mms.OutgoingAttachmentStoreImpl] connaît les répertoires Android
 * (`cacheDir` / `filesDir`). Le port ne manipule que des [File] (JDK), donc `domain/` reste sans
 * import Android.
 */
interface OutgoingAttachmentStore {

    /**
     * Déplace [staged] vers le stockage durable et renvoie le [File] durable. Idempotent si le
     * fichier est déjà durable (renvoyé inchangé). Fail-open : renvoie [staged] tel quel en cas
     * d'échec IO, pour que l'envoi puisse tout de même se poursuivre.
     */
    fun promoteToDurable(staged: File): File
}
