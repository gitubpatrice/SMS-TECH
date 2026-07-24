package com.filestech.sms.core.ext

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterNotNull

/**
 * SharedFlow factory tuned for one-shot UI events: no replay, suspend on overflow off.
 */
fun <T> oneShotEvents(): MutableSharedFlow<T> = MutableSharedFlow(
    replay = 0,
    extraBufferCapacity = 16,
    onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
)

fun <T> MutableSharedFlow<T>.asEvents(): SharedFlow<T> = asSharedFlow()

/** Convenience: drops null values from a Flow, returning a typed non-null Flow. */
fun <T : Any> Flow<T?>.notNull(): Flow<T> = filterNotNull()
