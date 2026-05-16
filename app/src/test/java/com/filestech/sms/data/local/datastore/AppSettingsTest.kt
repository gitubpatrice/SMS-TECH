package com.filestech.sms.data.local.datastore

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AppSettingsTest {

    @Test fun `defaults are conservative and privacy friendly`() {
        val s = AppSettings()
        // Notifications enabled by default but content preview can be capped
        assertThat(s.notifications.enabled).isTrue()
        // Security: lock disabled out-of-the-box (user has to enable) but FLAG_SECURE on
        assertThat(s.security.flagSecure).isTrue()
        // Vault locks on leave by default
        assertThat(s.security.lockVaultOnLeave).isTrue()
        // Sending: confirm before broadcast on by default (anti-misclick)
        assertThat(s.sending.confirmBeforeBroadcast).isTrue()
        // Backups: encrypted by default
        assertThat(s.backup.encrypt).isTrue()
        // Auto-delete OFF — no surprise data loss
        assertThat(s.security.autoDeleteOlderThanDays).isNull()
    }

    @Test fun `v1_3_1 reaction send defaults are explicit`() {
        val s = AppSettings()
        // ON by default: the user wants the recipient to see the reaction. The first send is
        // guarded by a confirm dialog so no silent billing surprise.
        assertThat(s.sending.sendReactionsToRecipient).isTrue()
        // Confirm dialog NEVER pre-dismissed at install: the very first reaction send must
        // always ask, regardless of upgrade path or default value drift.
        assertThat(s.sending.reactionConfirmDismissed).isFalse()
        // Cleanup anchor null at install: prevents the worker from auto-purging immediately
        // after the user enables retention (anchor is set on first worker tick, real purge
        // in 30 days). This guards against the v1.3.0 regression where `null` was read as 0.
        assertThat(s.security.lastAutoPurgeAt).isNull()
    }
}
