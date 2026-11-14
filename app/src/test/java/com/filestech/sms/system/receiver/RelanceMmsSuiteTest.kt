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
import org.junit.jupiter.api.Test

/**
 * 2026-11-14 — la suite d'un nouvel essai de téléchargement MMS ([relancerMms]), dans l'ordre où elle compte.
 *
 * La porte de l'essai AUTOMATIQUE est la présence de la notification du message : l'effacement d'urgence les
 * annule toutes, et une alarme restée armée ne doit pas recréer un message dans une base qu'on vient de vider.
 * Un test qui ne vérifierait que le chemin heureux laisserait passer exactement ce défaut.
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
        ),
    )

    private val notifier = mockk<MmsFailureNotifier>(relaxed = true)
    private val telechargees = mutableListOf<RelanceMms.Demande>()
    private var resultat: Outcome<Unit> = Outcome.Success(Unit)
    private val bloques = mutableListOf<String>()

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
    )

    /** Contrôle négatif de la porte : effacement d'urgence ou notification balayée — rien ne part. */
    @Test
    fun `essai automatique - notification retiree, rien n'est telecharge`() = runTest {
        every { notifier.relanceAffichee(demande.etiquette) } returns false

        relancer(automatique = true)

        assertThat(telechargees).isEmpty()
        assertThat(bloques).isEmpty()
    }

    @Test
    fun `essai automatique - notification affichee, le meme message est redemande et sa notification reste`() = runTest {
        every { notifier.relanceAffichee(demande.etiquette) } returns true

        relancer(automatique = true)

        assertThat(telechargees).containsExactly(demande)
        // La notification reste pendant l'essai : elle dit qu'un message attend, et un essai peut durer.
        verify(exactly = 0) { notifier.retirerRelance(any()) }
    }

    /** Le bouton ne dépend pas de la notification : la fenêtre d'explication l'envoie aussi, une fois celle-ci partie. */
    @Test
    fun `bouton Reessayer - la notification part d'abord, puis le message est redemande`() = runTest {
        every { notifier.relanceAffichee(any()) } returns false

        relancer(automatique = false)

        assertThat(telechargees).containsExactly(demande)
        verify { notifier.retirerRelance(demande.etiquette) }
    }

    /** L'expéditeur bloqué depuis l'échec : la liste noire est lue sur l'adresse SANS le suffixe de passerelle. */
    @Test
    fun `expediteur bloque depuis - rien n'est telecharge, la notification part`() = runTest {
        every { notifier.relanceAffichee(any()) } returns true

        relancer(automatique = true, bloque = true)

        assertThat(bloques).containsExactly("+15551234567")
        assertThat(telechargees).isEmpty()
        verify { notifier.retirerRelance(demande.etiquette) }
    }

    /** Un téléchargement qui ne démarre pas ne rend aucun résultat : sans notification, le message serait perdu. */
    @Test
    fun `telechargement qui ne demarre pas - le bouton revient`() = runTest {
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
    }

    @Test
    fun `telechargement lance - aucune notification d'echec ici, le resultat la decidera`() = runTest {
        relancer(automatique = false)

        coVerify(exactly = 0) { notifier.notifyFailure(any(), any(), any(), any(), any(), any(), any()) }
    }
}
