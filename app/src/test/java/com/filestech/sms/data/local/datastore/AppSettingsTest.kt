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
}
