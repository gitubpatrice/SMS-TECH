package com.filestech.sms.data.mms

import android.content.Context
import com.filestech.sms.domain.mms.OutgoingAttachmentStore
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Promotes an outgoing MMS attachment from its **volatile** staging cache into the app's
 * **durable** attachment store just before the message is committed to Room + dispatched.
 *
 * **Why** : the UI stages user-picked media in `cacheDir/media_outgoing/` and recorded voice
 * clips in `cacheDir/voice_mms/`. Those cache directories are intentionally prunable — they hold
 * *abandoned drafts* (the user backs out without sending), swept by
 * [com.filestech.sms.system.scheduler.TelephonySyncWorker.pruneStaleOutboundCaches] (24 h) and
 * wiped wholesale by [com.filestech.sms.security.AutoLockObserver] on every lock cycle. But once
 * a message is **sent**, its `AttachmentEntity.localUri` keeps pointing at that same cache path,
 * so the file it references gets deleted out from under the row — the bubble then renders an
 * empty tile when the thread is reopened later (bug pré-existant depuis v1.2.3).
 *
 * Moving the file into `filesDir/mms_attachments/` — the exact same durable root the **inbound**
 * path already uses (cf. [com.filestech.sms.system.receiver.MmsDownloadedReceiver] `persistAttachment`,
 * v1.14.7) — makes outgoing attachments survive identically to incoming ones: exempt from the
 * cache pruners / lock purge, declared in `file_provider_paths.xml` for share/open, whitelisted by
 * [MmsSystemWriteback] and [com.filestech.sms.ui.components.MediaAttachmentBubble], and wiped only
 * by [com.filestech.sms.security.PanicService].
 *
 * Fail-open by design: if the move cannot complete (IO error, rename + copy both fail), we return
 * the **original** staged file so the send still proceeds. The worst case then degrades to today's
 * pre-existing behaviour for that one file — never worse.
 */
@Singleton
class OutgoingAttachmentStoreImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : OutgoingAttachmentStore {

    /**
     * Moves [staged] into `filesDir/mms_attachments/` and returns the durable [File]. Idempotent
     * for files already inside the durable root (returned unchanged). Returns [staged] untouched on
     * any failure so the caller can still dispatch the MMS.
     */
    override fun promoteToDurable(staged: File): File {
        val dir = File(context.filesDir, ATTACHMENTS_DIR)
        // Already durable (e.g. re-entrancy) — nothing to do.
        val canonicalDir = runCatching { dir.canonicalPath }.getOrNull()
        val canonicalStaged = runCatching { staged.canonicalPath }.getOrNull()
        if (canonicalDir != null && canonicalStaged != null &&
            canonicalStaged.startsWith(canonicalDir + File.separator)
        ) {
            return staged
        }
        return try {
            if (!dir.exists()) dir.mkdirs()
            val ext = staged.extension.ifBlank { "bin" }
            val target = File(dir, "out-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.$ext")
            // Same partition (both under /data/data/<pkg>/), so rename normally succeeds; fall back
            // to copy + delete for the rare cross-volume / locked-file case.
            if (staged.renameTo(target)) {
                target
            } else {
                staged.copyTo(target, overwrite = false)
                runCatching { staged.delete() }
                target
            }
        } catch (t: Throwable) {
            Timber.w(t, "OutgoingAttachmentStore: promote failed for %s, keeping staged path", staged.name)
            staged
        }
    }

    private companion object {
        /**
         * Durable attachment root (filesDir). MUST stay in sync with the inbound
         * `MmsDownloadedReceiver.ATTACHMENTS_DIR`, the `<files-path name="attachments" .../>` entry
         * in `res/xml/file_provider_paths.xml`, and the wipe list in `PanicService.nukeEverything`.
         */
        const val ATTACHMENTS_DIR: String = "mms_attachments"
    }
}
