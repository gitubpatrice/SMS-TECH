package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.ScheduledMessageRepository
import com.filestech.sms.system.scheduler.ScheduledMessageScheduler
import javax.inject.Inject

class ScheduleMessageUseCase @Inject constructor(
    private val repo: ScheduledMessageRepository,
    private val scheduler: ScheduledMessageScheduler,
) {
    suspend operator fun invoke(
        conversationId: Long?,
        addresses: List<PhoneAddress>,
        body: String,
        whenEpochMillis: Long,
        subId: Int?,
    ): Outcome<Long> {
        return when (val r = repo.schedule(conversationId, addresses, body, whenEpochMillis, subId)) {
            is Outcome.Success -> {
                scheduler.scheduleAt(r.value, whenEpochMillis)
                r
            }
            is Outcome.Failure -> r
        }
    }
}
