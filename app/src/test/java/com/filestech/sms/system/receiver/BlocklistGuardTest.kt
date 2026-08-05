package com.filestech.sms.system.receiver

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.BlockedNumber
import com.filestech.sms.domain.repository.BlockedNumberRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * v1.27.2 (audit externe 2026-08-04, findings 2 et 5) — verrouille le SENS d'échec de la
 * consultation de liste noire sur les chemins de réception.
 *
 * Le contrat a trois faces, et chacune a déjà eu son défaut réel dans ce dépôt :
 *  - une exception rend « non bloqué » (le message est préservé — avant, le chemin SMS le
 *    perdait définitivement) ;
 *  - un `true` franc écarte toujours (le correctif ne doit pas désarmer la liste noire) ;
 *  - `CancellationException` traverse (un `runCatching` l'avalait côté MMS et transformait
 *    une annulation normale en « non bloqué »).
 */
class BlocklistGuardTest {

    private class FakeRepo(
        private val onIsBlocked: suspend (String) -> Boolean,
    ) : BlockedNumberRepository {
        override fun observe(): Flow<List<BlockedNumber>> = emptyFlow()
        override suspend fun isBlocked(rawNumber: String): Boolean = onIsBlocked(rawNumber)
        override suspend fun block(rawNumber: String, label: String?): Outcome<Unit> =
            error("not under test")
        override suspend fun unblock(rawNumber: String): Outcome<Unit> = error("not under test")
        override suspend fun mirrorFromSystem(rawNumber: String): Outcome<Unit> =
            error("not under test")
        override suspend fun blockedNormalizedSnapshot(): Set<String> = error("not under test")
        override suspend fun blockedRawSnapshot(): List<String> = emptyList()
    }

    @Test fun `blocked sender is still dropped`() = runTest {
        val repo = FakeRepo { true }
        assertThat(repo.isBlockedFailOpen("0612345678")).isTrue()
    }

    @Test fun `allowed sender passes`() = runTest {
        val repo = FakeRepo { false }
        assertThat(repo.isBlockedFailOpen("0612345678")).isFalse()
    }

    @Test fun `lookup failure fails OPEN so the message is preserved`() = runTest {
        val repo = FakeRepo { throw IllegalStateException("database is being repaired") }
        assertThat(repo.isBlockedFailOpen("0612345678")).isFalse()
    }

    @Test fun `cancellation is rethrown, not converted into not-blocked`() = runTest {
        val repo = FakeRepo { throw CancellationException("scope cancelled") }
        assertThrows<CancellationException> {
            repo.isBlockedFailOpen("0612345678")
        }
    }
}
