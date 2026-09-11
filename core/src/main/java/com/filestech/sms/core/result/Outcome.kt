package com.filestech.sms.core.result

/**
 * A typed result that carries an [AppError] on failure. Avoids overlap with Kotlin's
 * standard [kotlin.Result] which we don't want to leak through the public API.
 */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: AppError) : Outcome<Nothing>
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

inline fun <T, R> Outcome<T>.flatMap(transform: (T) -> Outcome<R>): Outcome<R> = when (this) {
    is Outcome.Success -> transform(value)
    is Outcome.Failure -> this
}

inline fun <T> Outcome<T>.onSuccess(action: (T) -> Unit): Outcome<T> = also {
    if (it is Outcome.Success) action(it.value)
}

inline fun <T> Outcome<T>.onFailure(action: (AppError) -> Unit): Outcome<T> = also {
    if (it is Outcome.Failure) action(it.error)
}

/**
 * v1.28.5 — une annulation REMONTE : elle n'est pas un echec metier. Avant, un export ou une
 * restauration interrompus s'affichaient « echec de stockage ». Cf. [runCatchingCancellable].
 */
inline fun <T> runCatchingOutcome(block: () -> T, errorMapper: (Throwable) -> AppError): Outcome<T> =
    try {
        Outcome.Success(block())
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (t: Throwable) {
        Outcome.Failure(errorMapper(t))
    }
