package com.filestech.sms.system.receiver

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.system.notifications.MmsFailureNotifier
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 2026-11-14 — la suite d'un nouvel essai de téléchargement MMS ([relancerMms]), dans l'ordre où elle compte.
 *
 * Ses portes : un effacement d'urgence survenu depuis l'échec, la notification de CE message encore affichée (pour
 * l'essai automatique comme pour le bouton), la liste noire, et un seul essai à la fois. Un test qui ne vérifierait
 * que le chemin heureux laisserait passer exactement les défauts qu'elles ferment (relectures du 2026-11-14).
 */
class RelanceMmsSuiteTest {

    private val demande = requireNotNull(
        RelanceMms.Demande.creer(
            adresse = "http://mmsc.example/abc",
            transactionId = "T1",
            expediteur = "+15551234567/TYPE=PLMN",
            subId = 1,
            transactionIdOctets = byteArrayOf(1, 2),
            tentative = 1,
            lanceeA = 1_000L,
        ),
    )

    private val notifier = mockk<MmsFailureNotifier>(relaxed = true)
    private val telechargees = mutableListOf<RelanceMms.Demande>()
    private var resultat: Outcome<Unit> = Outcome.Success(Unit)
    private val bloques = mutableListOf<String>()
    private var effaceDepuis = false

    @BeforeEach
    fun notificationAffichee() {
        RelanceMms.EnCours.oublierPourLesTests()
        every { notifier.relanceAffichee(demande.etiquette) } returns true
    }

    @AfterEach
    fun oublier() {
        RelanceMms.EnCours.oublierPourLesTests()
    }

    private suspend fun relancer(automatique: Boolean, bloque: Boolean = false) = relancerMms(
        demande = demande,
        automatique = automatique,
        notifier = notifier,
        estBloque = { adresse ->
            bloques += adresse
            bloque
        },
        telecharger = { d ->
            telechargees += d
            resultat
        },
        effaceDepuis = { lanceeA ->
            assertThat(lanceeA).isEqualTo(1_000L)
            effaceDepuis
        },
    )

    /** Contrôle négatif de la porte : effacement d'urgence ou notification balayée — rien ne part. */
    @Test
    fun `essai automatique - notification retiree, rien n'est telecharge`() = runTest {
        every { notifier.relanceAffichee(demande.etiquette) } returns false

        relancer(automatique = true)

        assertThat(telechargees).isEmpty()
        assertThat(bloques).isEmpty()
    }

    /**
     * Le bouton d'une fenêtre restée ouverte, alors que le message est arrivé entre-temps (sa notification est
     * partie) : il ne le redemande plus (relecture Claude).
     */
    @Test
    fun `bouton - notification retiree, rien n'est telecharge`() = runTest {
        every { notifier.relanceAffichee(demande.etiquette) } returns false

        relancer(automatique = false)

        assertThat(telechargees).isEmpty()
    }

    /** Un effacement d'urgence depuis l'échec : ni téléchargement, ni notification qui resterait. */
    @Test
    fun `effacement d'urgence depuis l'echec - rien, et la notification part`() = runTest {
        effaceDepuis = true

        relancer(automatique = false)

        assertThat(telechargees).isEmpty()
        verify { notifier.retirerRelance(demande.etiquette) }
    }

    @Test
    fun `essai automatique - notification affichee, le meme message est redemande et sa notification reste`() = runTest {
        relancer(automatique = true)

        assertThat(telechargees).containsExactly(demande)
        // La notification reste pendant l'essai : elle dit qu'un message attend, et un essai peut durer.
        verify(exactly = 0) { notifier.retirerRelance(any()) }
    }

    @Test
    fun `bouton Reessayer - la notification part d'abord, puis le message est redemande`() = runTest {
        relancer(automatique = false)

        assertThat(telechargees).containsExactly(demande)
        verify { notifier.retirerRelance(demande.etiquette) }
    }

    /** L'alarme et le bouton, ou deux appuis : un seul téléchargement tant que le premier n'a pas rendu son résultat. */
    @Test
    fun `un seul essai a la fois - le second attend le resultat du premier`() = runTest {
        relancer(automatique = true)
        relancer(automatique = false)

        assertThat(telechargees).hasSize(1)

        // Le résultat du premier est traité (MmsDownloadedReceiver rend l'essai) : un nouvel essai peut partir.
        RelanceMms.EnCours.rendre(demande.etiquette, lanceA = android.os.SystemClock.elapsedRealtime())
        relancer(automatique = false)

        assertThat(telechargees).hasSize(2)
    }

    /**
     * Seconde relecture GPT du 2026-11-14 : le résultat TARDIF d'un essai tenu pour perdu ne libère pas l'essai qui l'a
     * remplacé — sinon un troisième partirait pendant que le second court.
     */
    @Test
    fun `le resultat tardif d'un ancien essai ne libere pas le suivant`() {
        val t0 = 1_000_000L
        val apresPerte = t0 + RelanceMms.EnCours.DUREE_MAX_MS
        assertThat(RelanceMms.EnCours.prendre(demande.etiquette, t0)).isTrue()
        assertThat(RelanceMms.EnCours.prendre(demande.etiquette, apresPerte)).isTrue()

        // Le résultat du premier essai (lancé à t0) arrive enfin.
        RelanceMms.EnCours.rendre(demande.etiquette, lanceA = t0)

        assertThat(RelanceMms.EnCours.prendre(demande.etiquette, apresPerte + 1)).isFalse()
        // Celui du second, lui, le libère.
        RelanceMms.EnCours.rendre(demande.etiquette, lanceA = apresPerte)
        assertThat(RelanceMms.EnCours.prendre(demande.etiquette, apresPerte + 2)).isTrue()
    }

    /** Un essai dont le résultat ne viendra jamais ne bloque pas le message pour toujours. */
    @Test
    fun `un essai perdu est tenu pour fini apres la duree maximale`() {
        val t0 = 1_000_000L
        assertThat(RelanceMms.EnCours.prendre(demande.etiquette, t0)).isTrue()
        assertThat(RelanceMms.EnCours.prendre(demande.etiquette, t0 + 60_000L)).isFalse()
        assertThat(RelanceMms.EnCours.prendre(demande.etiquette, t0 + RelanceMms.EnCours.DUREE_MAX_MS)).isTrue()
    }

    /** L'expéditeur bloqué depuis l'échec : la liste noire est lue sur l'adresse SANS le suffixe de passerelle. */
    @Test
    fun `expediteur bloque depuis - rien n'est telecharge, la notification part`() = runTest {
        relancer(automatique = true, bloque = true)

        assertThat(bloques).containsExactly("+15551234567")
        assertThat(telechargees).isEmpty()
        verify { notifier.retirerRelance(demande.etiquette) }
    }

    /** Un téléchargement qui ne démarre pas ne rend aucun résultat : sans notification, le message serait perdu. */
    @Test
    fun `telechargement qui ne demarre pas - le bouton revient, et l'essai est rendu`() = runTest {
        resultat = Outcome.Failure(AppError.Validation("x"))
        coEvery {
            notifier.notifyFailure(any(), any(), any(), any(), any(), any(), any())
        } returns true

        relancer(automatique = false)

        coVerifyOrder {
            notifier.retirerRelance(demande.etiquette)
            notifier.notifyFailure(any(), any(), any(), any(), any(), any(), any())
        }
        coVerify {
            notifier.notifyFailure(
                reason = MmsFailureNotifier.Reason.DOWNLOAD_FAILED,
                senderAddress = demande.expediteur,
                relance = demande,
            )
        }
        assertThat(RelanceMms.EnCours.prendre(demande.etiquette)).isTrue()
    }

    @Test
    fun `telechargement lance - aucune notification d'echec ici, le resultat la decidera`() = runTest {
        relancer(automatique = false)

        assertThat(telechargees).hasSize(1)
        coVerify(exactly = 0) { notifier.notifyFailure(any(), any(), any(), any(), any(), any(), any()) }
    }
}
