package com.filestech.sms.core.ext

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * SharedFlow factory tuned for one-shot UI events: no replay, suspend on overflow off.
 */
fun <T> oneShotEvents(): MutableSharedFlow<T> = MutableSharedFlow(
    replay = 0,
    extraBufferCapacity = 16,
    onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
)

fun <T> MutableSharedFlow<T>.asEvents(): SharedFlow<T> = asSharedFlow()

// v1.28.12 (audit B2) — `Flow<T?>.notNull()` a été SUPPRIMÉ. Aucun appelant : les quelques
// endroits concernés utilisent `filterNotNull()` directement, que cet alias ne faisait que
// renommer. Un second nom pour un même geste finit toujours par se lire comme deux gestes.
