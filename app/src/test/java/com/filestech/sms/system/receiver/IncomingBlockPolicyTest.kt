package com.filestech.sms.system.receiver

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.BlockedNumber
import com.filestech.sms.domain.model.Contact
import com.filestech.sms.domain.repository.BlockedNumberRepository
import com.filestech.sms.domain.repository.ContactRepository
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * v1.28.3 (F26) — **« Bloquer les numéros inconnus » était une promesse de sécurité que rien ne
 * tenait.**
 *
 * Le réglage était affiché (`settings_block_unknown`, « Block unknown numbers »), persisté et
 * restitué — dix occurrences dans le dépôt — pour **zéro point d'effet et zéro test**.
 * `BlockedNumberRepository.isBlocked` n'interroge que la table des numéros explicitement bloqués
 * et n'a jamais consulté les réglages. L'utilisateur qui activait l'interrupteur ne changeait
 * rien.
 *
 * # Ce que ces tests verrouillent, dans l'ordre d'importance
 *
 * Le plus important n'est pas que le blocage fonctionne, c'est **le sens dans lequel il
 * échoue** : cf. [unePermissionContactsRefuseeNeBloqueRien]. Un repli fermé couperait la
 * totalité des SMS entrants dès que `READ_CONTACTS` est révoquée, en silence, et l'utilisateur
 * ne verrait qu'un téléphone qui ne reçoit plus rien.
 */
class IncomingBlockPolicyTest {

    private companion object {
        const val CONNU = "+33611111111"
        const val INCONNU = "+33622222222"
        const val BLOQUE = "+33633333333"
    }

    // ──────────────────────────── Doublures ────────────────────────────

    private class ListeNoire(private val bloques: Set<String> = emptySet()) : BlockedNumberRepository {
        override fun observe(): Flow<List<BlockedNumber>> = MutableStateFlow(emptyList())
        override suspend fun isBlocked(rawNumber: String): Boolean = rawNumber in bloques
        override suspend fun block(rawNumber: String, label: String?): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun unblock(rawNumber: String): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun mirrorFromSystem(rawNumber: String): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun blockedNormalizedSnapshot(): Set<String> = bloques
        override suspend fun blockedRawSnapshot(): List<String> = bloques.toList()
    }

    /** [leve] simule une permission contacts refusee ou revoquee. */
    private class Contacts(
        private val connus: Set<String> = emptySet(),
        private val leve: Boolean = false,
    ) : ContactRepository {
        override suspend fun lookupByPhone(rawPhone: String): Contact? {
            if (leve) throw SecurityException("READ_CONTACTS refusee")
            return if (rawPhone in connus) {
                Contact(
                    id = 1L,
                    displayName = "Alice",
                    phones = listOf(com.filestech.sms.domain.model.PhoneAddress.of(rawPhone)),
                )
            } else {
                null
            }
        }
        override suspend fun listAll(): List<Contact> = emptyList()
    }

    private class Reglages(bloquerInconnus: Boolean) : AppSettingsSource {
        private val etat = MutableStateFlow(
            AppSettings().let { it.copy(blocking = it.blocking.copy(blockUnknown = bloquerInconnus)) },
        )
        override val flow: Flow<AppSettings> = etat.asStateFlow()
        override val state: StateFlow<AppSettings> = etat.asStateFlow()
        override suspend fun hydratedOrNull(): AppSettings = etat.value
        override suspend fun update(transform: (AppSettings) -> AppSettings) { etat.value = transform(etat.value) }
    }

    private fun politique(
        bloques: Set<String> = emptySet(),
        connus: Set<String> = emptySet(),
        bloquerInconnus: Boolean = false,
        contactsLeve: Boolean = false,
    ) = IncomingBlockPolicy(ListeNoire(bloques), Contacts(connus, contactsLeve), Reglages(bloquerInconnus))

    // ──────────────────────────── Les tests ────────────────────────────

    @Test
    fun `le reglage actif ecarte un numero absent des contacts`() = runBlocking {
        val ecarte = politique(connus = setOf(CONNU), bloquerInconnus = true).doitEcarter(INCONNU)
        assertThat(ecarte).isTrue()
    }

    @Test
    fun `le reglage actif laisse passer un contact connu`() = runBlocking {
        val ecarte = politique(connus = setOf(CONNU), bloquerInconnus = true).doitEcarter(CONNU)
        assertThat(ecarte).isFalse()
    }

    /**
     * Controle negatif : le reglage DESACTIVE ne doit rien changer. Sans lui, une politique qui
     * ecarterait tous les inconnus en permanence passerait le premier test.
     */
    @Test
    fun `le reglage inactif laisse passer un inconnu`() = runBlocking {
        val ecarte = politique(bloquerInconnus = false).doitEcarter(INCONNU)
        assertThat(ecarte).isFalse()
    }

    /** La liste noire garde son effet, reglage actif ou non. */
    @Test
    fun `un numero de la liste noire est ecarte independamment du reglage`() = runBlocking {
        assertThat(politique(bloques = setOf(BLOQUE)).doitEcarter(BLOQUE)).isTrue()
        assertThat(politique(bloques = setOf(BLOQUE), bloquerInconnus = true).doitEcarter(BLOQUE)).isTrue()
    }

    /**
     * **Le test le plus important du fichier.**
     *
     * Si `READ_CONTACTS` est refusee ou revoquee, la recherche de contact leve pour TOUS les
     * numeros. Un repli ferme bloquerait alors la totalite des SMS entrants, en silence : le
     * telephone cesserait simplement de recevoir, sans que rien ne l'explique. Une permission
     * absente ne veut pas dire « cette personne est inconnue », elle veut dire « je ne sais
     * pas » — et on ne bloque pas sur une ignorance.
     */
    @Test
    fun `une permission contacts refusee ne bloque rien`() = runBlocking {
        val ecarte = politique(bloquerInconnus = true, contactsLeve = true).doitEcarter(INCONNU)
        assertThat(ecarte).isFalse()
    }

    /** Une adresse vide n'est pas un « inconnu » : c'est une absence d'information. */
    @Test
    fun `une adresse vide n est jamais ecartee`() = runBlocking {
        assertThat(politique(bloquerInconnus = true).doitEcarter("")).isFalse()
    }
}
