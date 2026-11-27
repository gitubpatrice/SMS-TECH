package com.filestech.sms.system.receiver

import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * 2026-11-27 (issue #98, profil secondaire) — la passe qui notifie, dans un profil secondaire, ce que le profil
 * principal a reçu ([passeDesArrivees]).
 *
 * Ce que ces cas fixent : la synchronisation passe AVANT la lecture ; le repère persistant fait notifier un message
 * déjà importé par une autre synchronisation (celle du démarrage du processus) et jamais deux fois le même ; la
 * première passe ne regarde que les dernières minutes ; une base recréée (repère au-delà du plafond) repart comme une
 * première passe ; un expéditeur écarté n'est pas notifié ; une notification en échec n'arrête pas les autres.
 */
class PasseDesArriveesTest {

    private val maintenant = 1_800_000_000_000L

    /** Une fausse base : les messages, et ce que la passe en demande. */
    private class Base(var messages: List<MessageEntity> = emptyList()) {
        val demandes = mutableListOf<Triple<Long, Long, Long>>()
        val limites = mutableListOf<Int>()
        var synchronisee = false
        fun plusGrandId() = messages.maxOfOrNull { it.id } ?: 0L
        fun candidats(apres: Long, jusqua: Long, depuis: Long, limite: Int): List<MessageEntity> {
            demandes += Triple(apres, jusqua, depuis)
            limites += limite
            return messages.filter { it.id in (apres + 1)..jusqua && it.date >= depuis }
                .sortedByDescending { it.id }
                .take(limite)
        }
    }

    private fun recu(id: Long, adresse: String = "+33612340098", date: Long = maintenant) = MessageEntity(
        id = id,
        conversationId = 1,
        telephonyUri = "content://sms/$id",
        address = adresse,
        body = "m$id",
        type = MessageType.SMS,
        direction = MessageDirection.INCOMING,
        date = date,
        dateSent = null,
        read = false,
        starred = false,
        status = MessageStatus.RECEIVED,
        attachmentsCount = 0,
    )

    private class Resultat(val notifies: List<Long>, val repere: Long?, val nombre: Int)

    private suspend fun passe(
        base: Base,
        repere: Long,
        importes: List<MessageEntity> = emptyList(),
        ecartees: Set<String> = emptySet(),
        enEchec: Set<Long> = emptySet(),
    ): Resultat {
        val notifies = mutableListOf<Long>()
        var pose: Long? = null
        val nombre = passeDesArrivees(
            repere = repere,
            maintenant = maintenant,
            synchroniser = {
                base.synchronisee = true
                base.messages = base.messages + importes
            },
            plusGrandId = { base.plusGrandId() },
            candidats = base::candidats,
            aEcarter = { it in ecartees },
            notifier = { message ->
                if (message.id in enEchec) error("notification refusee")
                notifies += message.id
            },
            poserRepere = { pose = it },
        )
        return Resultat(notifies, pose, nombre)
    }

    @Test
    fun laSynchronisationPasseAvant_etCeQuElleApporteEstNotifie() = runTest {
        val base = Base(listOf(recu(1), recu(2)))

        val r = passe(base, repere = 2L, importes = listOf(recu(3), recu(4)))

        assertThat(base.synchronisee).isTrue()
        assertThat(r.notifies).containsExactly(3L, 4L).inOrder()
        assertThat(r.repere).isEqualTo(4L)
        assertThat(r.nombre).isEqualTo(2)
    }

    /** Le cas mesuré : le SMS démarre le processus, dont la synchronisation l'importe AVANT la passe. */
    @Test
    fun unMessageDejaImporteParLeDemarrage_estQuandMemeNotifie() = runTest {
        val base = Base(listOf(recu(1), recu(2), recu(3)))

        val r = passe(base, repere = 2L)

        assertThat(r.notifies).containsExactly(3L)
        assertThat(r.repere).isEqualTo(3L)
    }

    @Test
    fun deuxPasses_neNotifientPasDeuxFoisLeMemeMessage() = runTest {
        val base = Base(listOf(recu(1)))
        val premiere = passe(base, repere = 0L, importes = listOf(recu(2)))

        val seconde = passe(base, repere = premiere.repere!!)

        assertThat(premiere.notifies).containsExactly(1L, 2L)
        assertThat(seconde.notifies).isEmpty()
        assertThat(seconde.repere).isNull()
    }

    @Test
    fun laPremierePasse_neRegardeQueLesDernieresMinutes() = runTest {
        val dixMinutes = 10L * 60L * 1_000L
        val vieux = recu(1, date = maintenant - dixMinutes - 1)
        val base = Base(listOf(vieux, recu(2)))

        val r = passe(base, repere = -1L)

        assertThat(r.notifies).containsExactly(2L)
        assertThat(base.demandes.single()).isEqualTo(Triple(0L, 2L, maintenant - dixMinutes))
        assertThat(r.repere).isEqualTo(2L)
    }

    /** Une journée, et vingt notifications au plus par passe (une sauvegarde restaurée d'un coup). */
    @Test
    fun ensuite_laFenetreEstCelleDUneJournee_etLaPasseEstBornee() = runTest {
        val base = Base(listOf(recu(1)))

        passe(base, repere = 0L)

        assertThat(base.demandes.single().third).isEqualTo(maintenant - 24L * 60L * 60L * 1_000L)
        assertThat(base.limites.single()).isEqualTo(20)
    }

    /** Base recréée : les identifiants repartent de 1, sous l'ancien repère. Sans ceci, rien avant de le passer. */
    @Test
    fun unRepereAuDelaDuPlafond_compteCommeJamaisPose() = runTest {
        val base = Base(listOf(recu(1), recu(2)))

        val r = passe(base, repere = 500L)

        assertThat(r.notifies).containsExactly(1L, 2L)
        assertThat(base.demandes.single().third).isEqualTo(maintenant - ArriveesDuProfilPrincipal.PREMIERE_FENETRE_MS)
        assertThat(r.repere).isEqualTo(2L)
    }

    @Test
    fun unExpediteurEcarte_nEstPasNotifie_maisLeRepereAvance() = runTest {
        val base = Base(listOf(recu(1, adresse = "+33600000001"), recu(2, adresse = "+33600000002")))

        val r = passe(base, repere = 0L, ecartees = setOf("+33600000001"))

        assertThat(r.notifies).containsExactly(2L)
        assertThat(r.repere).isEqualTo(2L)
    }

    @Test
    fun uneNotificationEnEchec_nArretePasLesAutres() = runTest {
        val base = Base(listOf(recu(1), recu(2), recu(3)))

        val r = passe(base, repere = 0L, enEchec = setOf(2L))

        assertThat(r.notifies).containsExactly(1L, 3L).inOrder()
        assertThat(r.nombre).isEqualTo(2)
        assertThat(r.repere).isEqualTo(3L)
    }

    @Test
    fun leTexteDUnSms_ouDUnMmsAvecTexte_estLeMessage() = runTest {
        var pieceLue = false
        val lire: suspend () -> String? = {
            pieceLue = true
            "image/jpeg"
        }

        assertThat(corpsDeNotification(MessageType.SMS, "Bonjour", lire)).isEqualTo("Bonjour")
        assertThat(corpsDeNotification(MessageType.MMS, "Photo du chalet", lire)).isEqualTo("Photo du chalet")
        assertThat(pieceLue).isFalse()
    }

    /** Le même libellé que le profil principal pour un MMS sans texte (`LecteurRetrieveConf.libelleParDefaut`). */
    @Test
    fun unMmsSansTexte_prendLeLibelleDeSaPremierePiece() = runTest {
        assertThat(corpsDeNotification(MessageType.MMS, " ") { "image/jpeg" }).isEqualTo("🖼️")
        assertThat(corpsDeNotification(MessageType.MMS, "") { "audio/amr" }).isEqualTo("🎤")
        assertThat(corpsDeNotification(MessageType.MMS, "") { null }).isEqualTo("[MMS]")
    }

    @Test
    fun rienDeNouveau_rienANotifier_etLeRepereNeBougePas() = runTest {
        val base = Base(listOf(recu(1)))

        val r = passe(base, repere = 1L)

        assertThat(r.notifies).isEmpty()
        assertThat(base.demandes).isEmpty()
        assertThat(r.repere).isNull()
    }
}
