package com.filestech.sms.system.share

import com.filestech.sms.system.share.IncomingShareHolder.Cible
import com.filestech.sms.system.share.IncomingShareHolder.Pending
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-11-20 (courriel : « Transférer » n'écrit rien dans la conversation choisie) — **un dépôt ne revient qu'à sa
 * conversation, et un dépôt abandonné ne va nulle part.**
 *
 * Ce que ces cas fixent : le transfert vers une conversation n'est repris que par elle (une autre le laisse en place) ;
 * un dépôt « en attente » ou « à choisir » n'est repris par personne avant son reciblage ; le reciblage ne détourne pas
 * un transfert déjà ciblé et ne ranime pas un dépôt expiré ; « Nouveau message » qui se ferme abandonne ce qui
 * l'attendait, et seulement ce qui lui est antérieur ; le verrouillage efface les transferts, pas les partages ; le
 * texte reçu s'ajoute à une saisie commencée au lieu d'être perdu.
 */
class IncomingShareHolderTest {

    private val maintenant = 1_800_000_000_000L

    private fun depot(cible: Cible, ilYaMs: Long = 0L) =
        Pending(uris = emptyList(), mimeType = null, text = "Bonjour", postedAt = maintenant - ilYaMs, cible = cible)

    @Test
    fun `un transfert n'est repris que par sa conversation`() {
        val relais = IncomingShareHolder().apply { set(depot(Cible.Conversation(7L))) }

        assertThat(relais.consumePour(8L, maintenant)).isNull()
        assertThat(relais.pending.value).isNotNull()

        assertThat(relais.consumePour(7L, maintenant)?.text).isEqualTo("Bonjour")
        assertThat(relais.pending.value).isNull()
        assertThat(relais.consumePour(7L, maintenant)).isNull()
    }

    /** Contrôle négatif : une reprise sans condition ferait tomber ce cas, comme avant (le relais servait au premier venu). */
    @Test
    fun `un depot en attente ou a choisir n'est repris par aucune conversation avant son reciblage`() {
        for (cible in listOf(Cible.EnAttente, Cible.AChoisir)) {
            val relais = IncomingShareHolder().apply { set(depot(cible)) }

            assertThat(relais.consumePour(7L, maintenant)).isNull()

            relais.cibler(7L, maintenant)
            assertThat(relais.consumePour(8L, maintenant)).isNull()
            assertThat(relais.consumePour(7L, maintenant)).isNotNull()
        }
    }

    @Test
    fun `le reciblage ne detourne pas un transfert deja destine a une conversation`() {
        val relais = IncomingShareHolder().apply { set(depot(Cible.Conversation(7L))) }

        relais.cibler(9L, maintenant)

        assertThat(relais.pending.value?.cible).isEqualTo(Cible.Conversation(7L))
    }

    /** Relecture Claude : un partage expiré pendant le verrouillage serait reparti vers le message suivant. */
    @Test
    fun `le reciblage ne ranime pas un depot expire, et fait repartir le delai`() {
        val expire = IncomingShareHolder().apply {
            set(depot(Cible.AChoisir, ilYaMs = IncomingShareHolder.PENDING_TTL_MS + 1))
        }
        expire.cibler(7L, maintenant)
        assertThat(expire.pending.value).isNull()

        val attente = IncomingShareHolder().apply { set(depot(Cible.EnAttente, ilYaMs = 5L * 60_000L)) }
        attente.cibler(7L, maintenant)
        assertThat(attente.pending.value?.postedAt).isEqualTo(maintenant)
        assertThat(attente.consumePour(7L, maintenant + 30_000L)).isNotNull()
    }

    @Test
    fun `un depot expire est efface sans etre rendu`() {
        val relais = IncomingShareHolder().apply {
            set(depot(Cible.Conversation(7L), ilYaMs = IncomingShareHolder.PENDING_TTL_MS + 1))
        }

        assertThat(relais.consumePour(7L, maintenant)).isNull()
        assertThat(relais.pending.value).isNull()
    }

    @Test
    fun `seul un depot en attente de sa conversation vit dix minutes`() {
        assertThat(depot(Cible.AChoisir, ilYaMs = 61_000L).isExpired(maintenant)).isTrue()
        assertThat(depot(Cible.Conversation(1L), ilYaMs = 61_000L).isExpired(maintenant)).isTrue()
        assertThat(depot(Cible.EnAttente, ilYaMs = 9L * 60_000L).isExpired(maintenant)).isFalse()
        assertThat(depot(Cible.EnAttente, ilYaMs = 11L * 60_000L).isExpired(maintenant)).isTrue()
    }

    /**
     * Relectures Claude et GPT : un transfert vers un nouveau contact, abandonné, partait vers le destinataire du
     * « Nouveau message » suivant. Contrôle négatif : sans [IncomingShareHolder.abandonnerNonCible], il reste.
     */
    @Test
    fun `Nouveau message qui se ferme abandonne ce qui l'attendait, et seulement ce qui lui est anterieur`() {
        for (cible in listOf(Cible.EnAttente, Cible.AChoisir)) {
            val ancien = IncomingShareHolder().apply { set(depot(cible, ilYaMs = 1_000L)) }
            ancien.abandonnerNonCible(ouvertA = maintenant - 500L)
            assertThat(ancien.pending.value).isNull()

            // Posé APRÈS l'ouverture de l'écran qui se ferme : il appartient à un écran plus récent.
            val recent = IncomingShareHolder().apply { set(depot(cible)) }
            recent.abandonnerNonCible(ouvertA = maintenant - 500L)
            assertThat(recent.pending.value).isNotNull()
        }
        // Déjà destiné à sa conversation (créée par cet écran) : rien ne l'efface.
        val cible = IncomingShareHolder().apply { set(depot(Cible.Conversation(7L), ilYaMs = 1_000L)) }
        cible.abandonnerNonCible(ouvertA = maintenant)
        assertThat(cible.pending.value).isNotNull()
    }

    @Test
    fun `le verrouillage efface les transferts, pas un partage venu d'une autre application`() {
        for (transfert in listOf(Cible.EnAttente, Cible.Conversation(7L))) {
            val relais = IncomingShareHolder().apply { set(depot(transfert)) }
            relais.abandonnerTransferts()
            assertThat(relais.pending.value).isNull()
        }
        val partage = IncomingShareHolder().apply { set(depot(Cible.AChoisir)) }
        partage.abandonnerTransferts()
        assertThat(partage.pending.value).isNotNull()
    }

    @Test
    fun `la session leurre efface tout, le verrou seulement les transferts`() {
        val leurre = IncomingShareHolder().apply { set(depot(Cible.AChoisir)) }
        leurre.surVerrouOuLeurre(verrouille = false, leurre = true)
        assertThat(leurre.pending.value).isNull()

        val verrou = IncomingShareHolder().apply { set(depot(Cible.EnAttente)) }
        verrou.surVerrouOuLeurre(verrouille = true, leurre = false)
        assertThat(verrou.pending.value).isNull()

        val ouvert = IncomingShareHolder().apply { set(depot(Cible.EnAttente)) }
        ouvert.surVerrouOuLeurre(verrouille = false, leurre = false)
        assertThat(ouvert.pending.value).isNotNull()
    }

    @Test
    fun `le texte d'un lien sms est depose pour sa cible, et rien sans texte`() {
        val relais = IncomingShareHolder()
        relais.deposerTexte(null, Cible.Conversation(7L))
        relais.deposerTexte("  ", Cible.Conversation(7L))
        assertThat(relais.pending.value).isNull()

        relais.deposerTexte("Bonjour", Cible.Conversation(7L))
        assertThat(relais.consumePour(7L)?.text).isEqualTo("Bonjour")
    }

    /** Contrôle négatif : l'ancienne règle (« seulement si la saisie est vide ») perdait ce texte. */
    @Test
    fun `le texte recu s'ajoute a une saisie commencee, sur une nouvelle ligne`() {
        assertThat(IncomingShareHolder.saisieAvec("", "Transfere")).isEqualTo("Transfere")
        assertThat(IncomingShareHolder.saisieAvec("   ", "Transfere")).isEqualTo("Transfere")
        assertThat(IncomingShareHolder.saisieAvec("Salut ", "Transfere")).isEqualTo("Salut\nTransfere")
    }
}
