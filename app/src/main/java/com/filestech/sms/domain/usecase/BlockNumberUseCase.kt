package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.repository.BlockedNumberRepository
import javax.inject.Inject

class BlockNumberUseCase @Inject constructor(
    private val repo: BlockedNumberRepository,
) {
    suspend operator fun invoke(rawNumber: String, label: String? = null): Outcome<Unit> =
        repo.block(rawNumber, label)
}

class UnblockNumberUseCase @Inject constructor(
    private val repo: BlockedNumberRepository,
) {
    suspend operator fun invoke(rawNumber: String): Outcome<Unit> = repo.unblock(rawNumber)
}
