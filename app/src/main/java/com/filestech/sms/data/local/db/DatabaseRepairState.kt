package com.filestech.sms.data.local.db

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishes whether the one-shot database repair ([LegacyZeroKeyRekey]) has settled.
 *
 * The repair has to run before Room opens the database, which makes it part of the very first
 * `AppDatabase` provision. On the single launch that actually rebuilds a legacy zero-key database
 * that work is measured in seconds — far too long to sit on the main thread. The startup sequence
 * therefore forces the provision onto an IO dispatcher and keeps the splash screen up until this
 * flag flips, rather than letting the UI race the migration.
 *
 * [markSettled] is called from a `finally` on purpose: a failed repair must not leave the splash
 * screen pinned forever. The failure surfaces through the thrown
 * [LegacyZeroKeyRekey.Failure] instead.
 */
@Singleton
class DatabaseRepairState @Inject constructor() {

    private val _settled = MutableStateFlow(false)

    /** `true` once the repair has run to completion — successfully or not. */
    val settled: StateFlow<Boolean> = _settled.asStateFlow()

    internal fun markSettled() {
        _settled.value = true
    }
}
