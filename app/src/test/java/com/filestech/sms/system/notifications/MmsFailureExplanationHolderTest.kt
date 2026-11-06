package com.filestech.sms.system.notifications

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-11-06 — le porteur de la fenêtre « MMS non reçu », et la lecture du motif porté par l'intent.
 *
 * Le défaut corrigé était un tap qui ne menait nulle part : la notification promettait des « paramètres
 * MMS » inexistants et ouvrait la liste des conversations. Un porteur qui perdrait son motif rétablirait
 * ce silence sans qu'aucun écran ne le montre ; d'où le sens du repli testé, et pas seulement le chemin
 * heureux.
 */
class MmsFailureExplanationHolderTest {

    private companion object {
        const val T0 = 1_786_000_000_000L
    }

    @Test
    fun `un motif pose puis consomme rend ce motif`() {
        val holder = MmsFailureExplanationHolder()

        holder.set(MmsFailureNotifier.Reason.TOO_LARGE, T0)

        assertThat(holder.consume(T0)?.reason).isEqualTo(MmsFailureNotifier.Reason.TOO_LARGE)
    }

    /** Sans cela, chaque recomposition rouvrirait la fenêtre que l'utilisateur vient de fermer. */
    @Test
    fun `une seconde consommation ne rouvre pas la fenetre`() {
        val holder = MmsFailureExplanationHolder()
        holder.set(MmsFailureNotifier.Reason.DOWNLOAD_FAILED, T0)

        holder.consume(T0)

        assertThat(holder.consume(T0)).isNull()
    }

    @Test
    fun `le dernier tap decide`() {
        val holder = MmsFailureExplanationHolder()

        holder.set(MmsFailureNotifier.Reason.TOO_LARGE, T0)
        holder.set(MmsFailureNotifier.Reason.DOWNLOAD_FAILED, T0)

        assertThat(holder.consume(T0)?.reason).isEqualTo(MmsFailureNotifier.Reason.DOWNLOAD_FAILED)
    }

    /** Un déverrouillage lent ne fait pas perdre la fenêtre : dix minutes, bornes comprises. */
    @Test
    fun `dans le delai - rendu, meme apres un deverrouillage lent`() {
        val holder = MmsFailureExplanationHolder()
        holder.set(MmsFailureNotifier.Reason.TOO_LARGE, T0)

        assertThat(holder.consume(T0 + MmsFailureExplanationHolder.PENDING_TTL_MS)?.reason)
            .isEqualTo(MmsFailureNotifier.Reason.TOO_LARGE)
    }

    /** Contrôle négatif du précédent : une milliseconde de plus, et le tap resté sans suite est oublié. */
    @Test
    fun `hors delai - rien, et le porteur est vide`() {
        val holder = MmsFailureExplanationHolder()
        holder.set(MmsFailureNotifier.Reason.TOO_LARGE, T0)

        assertThat(holder.consume(T0 + MmsFailureExplanationHolder.PENDING_TTL_MS + 1)).isNull()
        assertThat(holder.pending.value).isNull()
    }

    /** Relecture du 2026-11-06 : une fenêtre ouverte ne doit pas disparaître à une rotation, même tard. */
    @Test
    fun `fenetre affichee - ne perime plus`() {
        val holder = MmsFailureExplanationHolder()
        holder.set(MmsFailureNotifier.Reason.TOO_LARGE, T0)

        holder.marquerAffichee()

        assertThat(holder.pending.value?.isExpired(T0 + 10 * MmsFailureExplanationHolder.PENDING_TTL_MS)).isFalse()
        assertThat(holder.consume(T0 + 10 * MmsFailureExplanationHolder.PENDING_TTL_MS)?.reason)
            .isEqualTo(MmsFailureNotifier.Reason.TOO_LARGE)
    }

    /** Contrôle négatif : sans marquage, la même attente la périme. */
    @Test
    fun `fenetre jamais affichee - perime`() {
        val holder = MmsFailureExplanationHolder()
        holder.set(MmsFailureNotifier.Reason.TOO_LARGE, T0)

        assertThat(holder.pending.value?.isExpired(T0 + 10 * MmsFailureExplanationHolder.PENDING_TTL_MS)).isTrue()
    }

    @Test
    fun `marquer un porteur vide - sans effet`() {
        val holder = MmsFailureExplanationHolder()

        holder.marquerAffichee()

        assertThat(holder.pending.value).isNull()
    }

    /** Un nouveau tap repart de zéro : sa fenêtre n'a pas encore paru. */
    @Test
    fun `nouveau tap apres affichage - non marque`() {
        val holder = MmsFailureExplanationHolder()
        holder.set(MmsFailureNotifier.Reason.TOO_LARGE, T0)
        holder.marquerAffichee()

        holder.set(MmsFailureNotifier.Reason.DOWNLOAD_FAILED, T0)

        assertThat(holder.pending.value?.affichee).isFalse()
    }

    @Test
    fun `clear vide sans rendre`() {
        val holder = MmsFailureExplanationHolder()
        holder.set(MmsFailureNotifier.Reason.TOO_LARGE, T0)

        holder.clear()

        assertThat(holder.pending.value).isNull()
    }

    @Test
    fun `motif de l'intent - les deux motifs, par leur nom`() {
        MmsFailureNotifier.Reason.entries.forEach { reason ->
            assertThat(MmsFailureNotifier.raisonDepuisExtra(reason.name)).isEqualTo(reason)
        }
    }

    /** `MainActivity` est exportée : n'importe quelle application peut lui envoyer n'importe quoi. */
    @Test
    fun `motif de l'intent - absent, inconnu ou mal ecrit, rien`() {
        listOf(null, "", "too_large", "TOO_LARGE ", "SETTINGS", "0").forEach { valeur ->
            assertThat(MmsFailureNotifier.raisonDepuisExtra(valeur)).isNull()
        }
    }
}
