package com.filestech.sms.system.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.local.db.entity.MessageStatus
import com.filestech.sms.data.mms.MmsSender
import com.filestech.sms.data.mms.MmsSystemWriteback
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * Receives the dispatch result of an outgoing MMS sent via `SmsManager.sendMultimediaMessage`.
 *
 * On `RESULT_OK` :
 *   1. Flip the Room mirror row to SENT.
 *   2. Promote the `content://mms` row from OUTBOX to SENT.
 *   3. Replace the placeholder FROM address with the user's MSISDN (audit F4) so other SMS
 *      apps don't display `"insert-address-token"` literally.
 *
 * On failure :
 *   1. Flip Room to FAILED (keeping `mmsSystemId` so the next retry can purge the stale row).
 *   2. Delete the system OUTBOX row.
 *
 * Always (regardless of outcome): delete the transient PDU file the OS no longer needs.
 *
 * v1.2.6: flattened (audit Q10) — onReceive is now a thin dispatcher that delegates the async
 * branches to [handleOk] / [handleFailure].
 */
@AndroidEntryPoint
class MmsSentReceiver : BroadcastReceiver() {

    @Inject lateinit var mirror: ConversationMirror
    @Inject lateinit var systemWriteback: MmsSystemWriteback
    @Inject lateinit var settings: SettingsRepository
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MmsSender.ACTION_MMS_SENT) return

        // Audit F5 (v1.2.3) defense-in-depth: PendingIntent uses explicit `setClass` and the
        // receiver is `exported = false`. We still verify the resolved component package
        // matches ours so a future drift (accidental export) can't let a forged broadcast
        // mutate row state via mmsSystemId.
        intent.component
            ?.takeIf { it.packageName != context.packageName }
            ?.let {
                Timber.w("MmsSentReceiver: rejecting broadcast for foreign package %s", it.packageName)
                return
            }

        val localId = intent.getLongExtra(MmsSender.EXTRA_LOCAL_ID, -1L)
        val pduPath = intent.getStringExtra(MmsSender.EXTRA_PDU_FILE)
        val mmsSystemId = intent.getLongExtra(MmsSender.EXTRA_MMS_SYSTEM_ID, -1L)
        val rc = resultCode

        val pending = goAsync()
        scope.launch {
            try {
                if (localId >= 0) {
                    if (rc == Activity.RESULT_OK) {
                        handleOk(localId, mmsSystemId)
                    } else {
                        handleFailure(localId, mmsSystemId, rc)
                    }
                }
                // The OS keeps its own copy of the PDU through the dispatch pipeline; ours
                // is no longer needed regardless of outcome.
                pduPath?.let { runCatching { File(it).delete() } }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handleOk(localId: Long, mmsSystemId: Long) {
        mirror.updateOutgoingStatus(localId, MessageStatus.SENT)
        if (mmsSystemId <= 0L) return

        runCatching { systemWriteback.markSent(mmsSystemId) }
            .onFailure { Timber.w(it, "markSent(%d) failed", mmsSystemId) }

        // Audit F4 (v1.2.6) — replace the FROM placeholder with the user-configured MSISDN
        // if one was provided in Settings. No-op silencieux sinon — la convention AOSP de
        // skipper le token à l'import couvre déjà le cas dégradé.
        val msisdn = runCatching { settings.flow.first().sending.userMsisdn }.getOrNull()
        if (!msisdn.isNullOrBlank()) {
            runCatching { systemWriteback.finalizeFromAddress(mmsSystemId, msisdn) }
                .onFailure { Timber.w(it, "finalizeFromAddress(%d) failed", mmsSystemId) }
        }
    }

    private suspend fun handleFailure(localId: Long, mmsSystemId: Long, rc: Int) {
        Timber.w("MMS sent failed for id=%d resultCode=%d", localId, rc)
        mirror.updateOutgoingStatus(localId, MessageStatus.FAILED, errorCode = rc)
        if (mmsSystemId > 0L) {
            // Important : on garde `mmsSystemId` côté Room pour que le prochain retry puisse
            // purger cette row stale avant d'en insérer une nouvelle (audit F2 idempotence).
            // C'est la suppression côté system-provider qui se fait ici.
            runCatching { systemWriteback.delete(mmsSystemId) }
                .onFailure { Timber.w(it, "delete(%d) failed", mmsSystemId) }
        }
    }
}
