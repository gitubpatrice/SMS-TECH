package com.filestech.sms.system.scheduler

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.25.3 — `rescheduleAllPending` décide de re-planifier ou non un envoi à partir des tags que
 * WorkManager lui rend. Se tromper d'un cas a des conséquences opposées et toutes deux mauvaises :
 * confondre le tag commun avec un id ferait sauter la re-planification d'un envoi réellement
 * orphelin, et rater un id ferait écraser un backoff en cours.
 */
class ScheduledMessageSchedulerTagsTest {

    @Test
    fun `un tag d'id rend l'id`() {
        assertThat(ScheduledMessageSchedulerImpl.scheduledIdFromTag("scheduled_sms_42")).isEqualTo(42L)
    }

    @Test
    fun `le tag commun ne rend pas d'id`() {
        // Il commence par le même préfixe que les tags d'id — c'est le piège du parsing.
        assertThat(ScheduledMessageSchedulerImpl.scheduledIdFromTag("scheduled_sms_all")).isNull()
    }

    @Test
    fun `le tag de classe ajoute d office par WorkManager ne rend pas d id`() {
        val classTag = "com.filestech.sms.system.scheduler.ScheduledMessageWorker"
        assertThat(ScheduledMessageSchedulerImpl.scheduledIdFromTag(classTag)).isNull()
    }

    @Test
    fun `un tag etranger ne rend pas d id`() {
        assertThat(ScheduledMessageSchedulerImpl.scheduledIdFromTag("autre_chose_7")).isNull()
        assertThat(ScheduledMessageSchedulerImpl.scheduledIdFromTag("")).isNull()
    }

    @Test
    fun `un tag purement numerique sans prefixe ne rend pas d id`() {
        // C'est ce cas seul qui justifie le garde `takeIf { it != tag }` : sans lui,
        // `removePrefix` laisse la chaîne intacte et `"42".toLongOrNull()` rendrait 42 pour un
        // tag qui n'est pas le nôtre. Les autres cas de ce test passent même sans le garde.
        assertThat(ScheduledMessageSchedulerImpl.scheduledIdFromTag("42")).isNull()
    }
}
