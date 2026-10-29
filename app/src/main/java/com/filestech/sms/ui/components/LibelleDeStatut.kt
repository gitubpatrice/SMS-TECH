package com.filestech.sms.ui.components

import androidx.annotation.StringRes
import com.filestech.sms.R
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.model.SendErrorCode
import java.util.Locale

/**
 * 2026-10-29 (issue #53) — ce que dit la ligne sous une bulle ([MessageStatusLine]) : un libellé,
 * et le statut de l'opérateur (« 0x87 ») s'il a refusé le message.
 *
 * Un MMS en échec dit « Non envoyé », sans promettre « appuyer pour réessayer » :
 * `RetrySendUseCase` refuse de relancer un MMS (`MmsRetryUnsupported`), et la bulle vocale le
 * promettait quand même. Le libellé d'un SMS ne change pas.
 */
internal data class LibelleDeStatut(@StringRes val res: Int, val codeOperateur: String? = null)

internal fun libelleDeStatut(message: Message): LibelleDeStatut? = when (message.status) {
    Message.Status.FAILED -> {
        val refus = SendErrorCode.mmscRefusalStatus(message.errorCode)
        when {
            // Locale.ROOT : le code se lit tel qu'un opérateur l'écrit, quelle que soit la langue.
            refus != null -> LibelleDeStatut(
                R.string.thread_status_refused_by_carrier,
                "0x%02X".format(Locale.ROOT, refus),
            )
            message.type == Message.Type.MMS -> LibelleDeStatut(R.string.thread_status_failed_mms)
            else -> LibelleDeStatut(R.string.thread_status_failed)
        }
    }
    Message.Status.DELIVERED -> LibelleDeStatut(R.string.thread_status_delivered)
    Message.Status.SENT -> LibelleDeStatut(R.string.thread_status_sent)
    Message.Status.PENDING -> LibelleDeStatut(R.string.thread_status_pending)
    else -> null
}
