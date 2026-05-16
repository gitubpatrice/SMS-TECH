package com.filestech.sms.system.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.MessageStatus
import com.filestech.sms.data.mms.MmsSender
import com.filestech.sms.data.mms.MmsSystemWriteback
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    @Inject lateinit var messageDao: MessageDao
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
        // v1.2.7 audit Q5 — anti-broadcast-tardif. Sous Doze ou throttling Samsung One UI, un
        // result-broadcast d'une PREMIÈRE tentative peut arriver APRÈS qu'un retry a déjà
        // succès et que Room pointe sur un autre mmsSystemId. Sans ce check, on flippe SENT
        // → FAILED (ou pire) en se basant sur une row historique. On confronte l'mmsSystemId
        // du broadcast à celui actuellement persisté en Room : pas match = broadcast obsolète,
        // on ignore silencieusement.
        if (mmsSystemId > 0L && !matchesCurrentRow(localId, mmsSystemId)) {
            Timber.i("handleOk(local=%d, sys=%d) skipped — stale broadcast vs current Room row", localId, mmsSystemId)
            return
        }

        mirror.updateOutgoingStatus(localId, MessageStatus.SENT)
        if (mmsSystemId <= 0L) return

        runCatching { systemWriteback.markSent(mmsSystemId) }
            .onFailure { Timber.w(it, "markSent(%d) failed", mmsSystemId) }

        // Audit F4 (v1.2.6) — replace the FROM placeholder with the user-configured MSISDN
        // if one was provided in Settings. No-op silencieux sinon — la convention AOSP de
        // skipper le token à l'import couvre déjà le cas dégradé.
        //
        // v1.2.7 audit Q9 : DataStore peut stall plusieurs secondes au boot froid. Le receiver
        // a un budget de 10 s avant que le system reaper le kill ; on cap à 3 s pour cette
        // lecture non-critique et on skip silencieusement si timeout — pas de finalize, mais
        // le MMS reste correctement SENT.
        val msisdn = withTimeoutOrNull(SETTINGS_READ_TIMEOUT_MS) {
            runCatching { settings.flow.first().sending.userMsisdn }.getOrNull()
        }
        if (!msisdn.isNullOrBlank()) {
            runCatching { systemWriteback.finalizeFromAddress(mmsSystemId, msisdn) }
                .onFailure { Timber.w(it, "finalizeFromAddress(%d) failed", mmsSystemId) }
        }
    }

    /**
     * v1.2.7 audit Q5 — compare l'`mmsSystemId` reçu par broadcast à la valeur actuellement
     * persistée dans Room. Si différentes, le broadcast vient d'une tentative antérieure et
     * doit être ignoré (la row Room a été reprise par un retry ultérieur). Retourne `true` si
     * on peut traiter (id matche ou Room n'a plus de valeur → broadcast peut être traité).
     */
    private suspend fun matchesCurrentRow(localId: Long, broadcastSystemId: Long): Boolean {
        val current = runCatching { messageDao.findMmsSystemId(localId) }.getOrNull()
        // current == null : la row Room n'a plus de mmsSystemId (rollback ou clear panic).
        //   Dans ce cas on ne devrait pas mettre à jour, donc on retourne false.
        // current == broadcastSystemId : match, on peut traiter.
        return current != null && current == broadcastSystemId
    }

    private companion object {
        /** Budget anti-ANR pour lire le `userMsisdn` du DataStore (audit Q9). */
        const val SETTINGS_READ_TIMEOUT_MS: Long = 3_000L
    }

    private suspend fun handleFailure(localId: Long, mmsSystemId: Long, rc: Int) {
        // v1.2.7 audit Q5 — même garde que handleOk. Un broadcast FAILED retardé venant d'une
        // tentative ancienne ne doit pas flipper l'état actuel (qui peut être déjà SENT pour
        // un retry plus récent).
        if (mmsSystemId > 0L && !matchesCurrentRow(localId, mmsSystemId)) {
            Timber.i("handleFailure(local=%d, sys=%d, rc=%d) skipped — stale broadcast", localId, mmsSystemId, rc)
            return
        }

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
