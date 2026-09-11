package com.filestech.sms.core.result

import kotlin.coroutines.cancellation.CancellationException

/**
 * v1.28.5 (balayage des 245 `runCatching` du dépôt) — **`runCatching` qui laisse passer une
 * annulation.**
 *
 * `runCatching` attrape `Throwable`, donc aussi `CancellationException` : autour d'un appel
 * `suspend`, une coroutine annulée voit son annulation transformée en « échec », journalisée
 * comme telle, et le repli s'exécute sur une coroutine morte. Mesuré dans le balayage : un cache
 * négatif empoisonné, des fichiers de pièces jointes jamais effacés, une région de numérotation
 * fausse figée dans un instantané, et surtout quatre écritures de « supprimer toutes mes
 * données » avalées en silence. La règle du dépôt — *ne jamais avaler une annulation autour d'un
 * suspend* — était écrite à la main à chaque site, donc oubliée à la plupart.
 *
 * Même contrat que `runCatching`, à une exception près : une [CancellationException] remonte.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }
