package com.filestech.sms.data.sms

import android.content.ContentResolver
import android.database.Cursor
import com.filestech.sms.domain.model.MessageDirection
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * 2026-11-06 (issue #76) — [readMmsAddresses] : l'adresse de la conversation ET les destinataires d'un
 * envoi groupé, en une lecture de `content://mms/{id}/addr`.
 *
 * L'adresse suit exactement la règle de [readMmsAddress] (l'effaceur en dépend) ; les destinataires sont les
 * `To` (151) puis les `Cc` (130), nettoyés comme ceux d'un PDU reçu ; le type 129, que certaines ROM donnent
 * à l'expéditeur, n'y entre pas.
 */
class AdressesMmsTest {

    private companion object {
        const val EXPEDITEUR = "+33617332729"
        const val MOI = "+33607231541"
        const val MARIE = "+33698765432"
        const val LEO = "+33611223344"
        const val FROM = 137
        const val TO = 151
        const val CC = 130
        const val GENERIQUE = 129
    }

    /** Un fournisseur dont la table `addr` contient [lignes] (adresse, type). */
    private fun resolver(vararg lignes: Pair<String?, Int>): ContentResolver {
        val curseur = mockk<Cursor>(relaxed = true)
        var i = -1
        every { curseur.moveToNext() } answers { ++i < lignes.size }
        every { curseur.getString(0) } answers { lignes[i].first }
        every { curseur.getInt(1) } answers { lignes[i].second }
        return mockk {
            every { query(any(), any(), any(), any(), any()) } returns curseur
        }
    }

    @Test
    fun `recu en groupe - l'expediteur, et tous les destinataires dont moi`() {
        val r = resolver(EXPEDITEUR to FROM, MOI to TO, MARIE to TO, LEO to CC)

        val a = checkNotNull(readMmsAddresses(r, 1L, MessageDirection.INCOMING))

        assertThat(a.adresse).isEqualTo(EXPEDITEUR)
        assertThat(a.destinataires).containsExactly(MOI, MARIE, LEO).inOrder()
    }

    /** Contrôle négatif : un MMS adressé à moi seul n'a pas de destinataires à garder. */
    @Test
    fun `recu a moi seul - pas d'envoi groupe`() {
        val a = checkNotNull(readMmsAddresses(resolver(EXPEDITEUR to FROM, MOI to TO), 1L, MessageDirection.INCOMING))

        assertThat(a.adresse).isEqualTo(EXPEDITEUR)
        assertThat(a.destinataires).isNull()
    }

    /** Un MMS envoyé à plusieurs depuis une autre app : la conversation reste au premier, la liste garde tous. */
    @Test
    fun `envoye a plusieurs - premier destinataire, et la liste entiere`() {
        val r = resolver("insert-address-token" to FROM, MARIE to TO, LEO to TO)

        val a = checkNotNull(readMmsAddresses(r, 1L, MessageDirection.OUTGOING))

        assertThat(a.adresse).isEqualTo(MARIE)
        assertThat(a.destinataires).containsExactly(MARIE, LEO).inOrder()
    }

    @Test
    fun `suffixe de passerelle retire des destinataires`() {
        val r = resolver(EXPEDITEUR to FROM, "$MOI/TYPE=PLMN" to TO, "$MARIE/TYPE=PLMN" to TO)

        assertThat(readMmsAddresses(r, 1L, MessageDirection.INCOMING)?.destinataires)
            .containsExactly(MOI, MARIE).inOrder()
    }

    /** Le type 129 sert de repli d'EXPÉDITEUR sur certaines ROM : il ne doit pas devenir un destinataire. */
    @Test
    fun `le type generique reste l'expediteur, pas un destinataire`() {
        val r = resolver(EXPEDITEUR to GENERIQUE, MOI to TO, MARIE to TO)

        val a = checkNotNull(readMmsAddresses(r, 1L, MessageDirection.INCOMING))

        assertThat(a.adresse).isEqualTo(EXPEDITEUR)
        assertThat(a.destinataires).containsExactly(MOI, MARIE).inOrder()
    }

    @Test
    fun `readMmsAddress rend la meme adresse`() {
        val lignes = arrayOf<Pair<String?, Int>>(EXPEDITEUR to FROM, MOI to TO, MARIE to TO)

        assertThat(readMmsAddress(resolver(*lignes), 1L, MessageDirection.INCOMING)).isEqualTo(EXPEDITEUR)
        assertThat(readMmsAddress(resolver(*lignes), 1L, MessageDirection.OUTGOING)).isEqualTo(MOI)
    }

    @Test
    fun `requete en echec - null, pas une liste vide`() {
        val r = mockk<ContentResolver> { every { query(any(), any(), any(), any(), any()) } returns null }

        assertThat(readMmsAddresses(r, 1L, MessageDirection.INCOMING)).isNull()
    }
}
