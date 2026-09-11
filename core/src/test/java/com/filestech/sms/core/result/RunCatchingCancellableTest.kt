package com.filestech.sms.core.result

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.coroutines.cancellation.CancellationException

/**
 * v1.28.5 — **une annulation n'est pas un échec.** `runCatching` les confondait ; le balayage
 * des 245 sites du dépôt a montré ce que ça coûtait. Ces cas fixent le contrat des deux aides,
 * avec le contrôle positif sans lequel un helper qui rethrow TOUT passerait le premier test.
 */
class RunCatchingCancellableTest {

    @Test
    fun `une annulation remonte`() {
        assertThrows<CancellationException> {
            runCatchingCancellable { throw CancellationException("ANNULEE") }
        }
    }

    /** Contrôle positif : un vrai échec est capturé, comme avec `runCatching`. */
    @Test
    fun `un echec ordinaire est capture`() {
        val r = runCatchingCancellable<Int> { error("ECHEC") }

        assertThat(r.isFailure).isTrue()
        assertThat(r.exceptionOrNull()).hasMessageThat().isEqualTo("ECHEC")
        assertThat(runCatchingCancellable { 7 }.getOrThrow()).isEqualTo(7)
    }

    @Test
    fun `runCatchingOutcome laisse remonter l'annulation et capture le reste`() {
        assertThrows<CancellationException> {
            runCatchingOutcome<Int>(
                block = { throw CancellationException("ANNULEE") },
                errorMapper = { AppError.Storage(it) },
            )
        }

        val echec = runCatchingOutcome<Int>(
            block = { error("ECHEC") },
            errorMapper = { AppError.Validation(it.message.orEmpty()) },
        )

        assertThat(echec).isEqualTo(Outcome.Failure(AppError.Validation("ECHEC")))
    }
}
