package com.filestech.sms.data.sync

import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.data.sms.PhoneIdentity
import com.filestech.sms.data.sms.TelephonyReader
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.SendingSettings
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * 2026-11-06 — [FilsDeGroupe] : quels fils du fournisseur sont des groupes, et la passe qui les range.
 *
 * Ce que ces cas fixent : seuls les fils d'au moins deux membres sont rendus, « Mon numéro » retiré ; un
 * fournisseur illisible ou qui lève rend `null` (l'import range alors comme avant) ; une lecture des messages
 * impossible rend `false` sans rien déplacer, pour que le marqueur ne soit pas posé ; une passe sans groupe aboutit
 * sans rien lire.
 */
class FilsDeGroupeTest {

    private companion object {
        const val MOI = "+15551230000"
        const val ALICE = "+15551230001"
        const val BOB = "+15551230002"
    }

    private val reader = mockk<TelephonyReader>()
    private val mirror = mockk<ConversationMirror>()
    private val settings = mockk<SettingsRepository>()
    private val identite = mockk<PhoneIdentity>()

    private fun fils(monNumero: String? = null): FilsDeGroupe {
        every { settings.flow } returns flowOf(AppSettings(sending = SendingSettings(userMsisdn = monNumero)))
        val snapshot = mockk<PhoneIdentity.Snapshot>()
        every { snapshot.key(any()) } answers { firstArg() }
        coEvery { identite.snapshot() } returns snapshot
        return FilsDeGroupe(reader, mirror, settings, identite)
    }

    @Test
    fun `seuls les fils de groupe sont rendus, mon numero retire`() = runTest {
        every { reader.lireFilsDuFournisseur() } returns mapOf(
            50L to listOf(ALICE, BOB),
            12L to listOf(BOB),
            60L to listOf(MOI, ALICE),
            70L to listOf(MOI, ALICE, BOB),
        )

        assertThat(fils(monNumero = MOI).lire()).containsExactly(50L, listOf(ALICE, BOB), 70L, listOf(ALICE, BOB))
    }

    /** Contrôle négatif du retrait : sans « Mon numéro », le fil {moi, Alice} reste tel que le fournisseur le dit. */
    @Test
    fun `sans mon numero, rien n'est retire`() = runTest {
        every { reader.lireFilsDuFournisseur() } returns mapOf(60L to listOf(MOI, ALICE))

        assertThat(fils().lire()).containsExactly(60L, listOf(MOI, ALICE))
    }

    @Test
    fun `fournisseur illisible ou qui leve - null`() = runTest {
        every { reader.lireFilsDuFournisseur() } returns null
        assertThat(fils().lire()).isNull()

        every { reader.lireFilsDuFournisseur() } throws SecurityException("READ_SMS")
        assertThat(fils().lire()).isNull()
    }

    @Test
    fun `chaque fil de groupe est range avec ses membres`() = runTest {
        every { reader.lireMessagesDesFils(setOf(50L, 70L)) } returns mapOf(
            50L to listOf("content://mms/7", "content://sms/3"),
            70L to listOf("content://mms/9"),
        )
        coEvery { mirror.regrouperFil(any(), any(), any()) } returns 1

        assertThat(fils().regrouper(mapOf(50L to listOf(ALICE, BOB), 70L to listOf(BOB, ALICE)))).isTrue()

        coVerify { mirror.regrouperFil(50L, listOf(ALICE, BOB), listOf("content://mms/7", "content://sms/3")) }
        coVerify { mirror.regrouperFil(70L, listOf(BOB, ALICE), listOf("content://mms/9")) }
    }

    /** Un fil en échec n'arrête pas les autres, et la passe aboutit : sinon chaque synchronisation la rejouerait. */
    @Test
    fun `un fil en echec - les autres sont ranges, la passe aboutit`() = runTest {
        every { reader.lireMessagesDesFils(setOf(50L, 70L)) } returns mapOf(
            50L to listOf("content://mms/7"),
            70L to listOf("content://mms/9"),
        )
        coEvery { mirror.regrouperFil(50L, any(), any()) } throws IllegalStateException("contrainte")
        coEvery { mirror.regrouperFil(70L, any(), any()) } returns 1

        assertThat(fils().regrouper(mapOf(50L to listOf(ALICE, BOB), 70L to listOf(BOB, ALICE)))).isTrue()

        coVerify { mirror.regrouperFil(70L, listOf(BOB, ALICE), listOf("content://mms/9")) }
    }

    @Test
    fun `messages illisibles - false, rien ne bouge`() = runTest {
        every { reader.lireMessagesDesFils(any()) } returns null

        assertThat(fils().regrouper(mapOf(50L to listOf(ALICE, BOB)))).isFalse()

        coVerify(exactly = 0) { mirror.regrouperFil(any(), any(), any()) }
    }

    @Test
    fun `aucun fil de groupe - la passe aboutit sans rien lire`() = runTest {
        assertThat(fils().regrouper(emptyMap())).isTrue()

        verify(exactly = 0) { reader.lireMessagesDesFils(any()) }
    }
}
