package com.filestech.sms.system.scheduler

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-11-15 (v1.30.1) — la copie système d'un MMS sortant ne doit pas être purgée tant qu'Android peut
 * encore rendre son verdict : sinon un MMS finalement envoyé reste « envoyé » dans SMS Tech mais disparaît
 * de `content://mms`. Cf. [TelephonySyncWorker.OUTBOX_PURGE_AGE_MS].
 */
class DelaiPurgeOutboxTest {

    @Test
    fun `la purge de l'OUTBOX attend au-dela du verdict du service MMS`() {
        assertThat(TelephonySyncWorker.OUTBOX_PURGE_AGE_MS)
            .isGreaterThan(TelephonySyncWorker.ATTENTE_MAX_SERVICE_MMS_MS)
    }
}
