package com.filestech.sms.system.notifications

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-11-06 — le porteur de la fenêtre « MMS non reçu », et la lecture du motif porté par l'intent.
 *
 * Le défaut corrigé était un tap qui ne menait nulle part : la notification promettait des « paramètres
 * MMS » inexistants et ouvrait la liste des conversations. Un porteur qui perdrait son motif rétablirait
 * ce silence sans qu'aucun écran ne le montre ; d'où le sens du repli testé, et pas seulement le chemin
 * heureux. 2026-11-06 — et la validation du code d'Android qu'il porte pour un téléchargement manqué.
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

    // 2026-11-06 — le code d'Android d'un téléchargement manqué, porté jusqu'à la fenêtre (Visible/Verizon).

    @Test
    fun `l'erreur posee avec le motif est rendue`() {
        val holder = MmsFailureExplanationHolder()

        holder.set(MmsFailureNotifier.Reason.DOWNLOAD_FAILED, T0, MmsFailureNotifier.ErreurAndroid(4, 403))

        assertThat(holder.consume(T0)?.erreur).isEqualTo(MmsFailureNotifier.ErreurAndroid(4, 403))
    }

    /** Le dernier tap décide, erreur comprise : sinon la fenêtre afficherait le code d'un autre message. */
    @Test
    fun `un tap sans code n'herite pas du code du tap precedent`() {
        val holder = MmsFailureExplanationHolder()
        holder.set(MmsFailureNotifier.Reason.DOWNLOAD_FAILED, T0, MmsFailureNotifier.ErreurAndroid(4, 403))

        holder.set(MmsFailureNotifier.Reason.DOWNLOAD_FAILED, T0)

        assertThat(holder.consume(T0)?.erreur).isNull()
    }

    @Test
    fun `erreur d'Android - code et statut HTTP dans leurs plages, gardes tels quels`() {
        val echec = MmsFailureNotifier.Reason.DOWNLOAD_FAILED
        assertThat(MmsFailureNotifier.ErreurAndroid.valide(echec, 1, null))
            .isEqualTo(MmsFailureNotifier.ErreurAndroid(1))
        assertThat(MmsFailureNotifier.ErreurAndroid.valide(echec, 99, 0))
            .isEqualTo(MmsFailureNotifier.ErreurAndroid(99))
        listOf(100, 403, 599).forEach { http ->
            assertThat(MmsFailureNotifier.ErreurAndroid.valide(echec, 4, http))
                .isEqualTo(MmsFailureNotifier.ErreurAndroid(4, http))
        }
    }

    /**
     * Contrôle négatif : `MainActivity` est exportée. -1 est `Activity.RESULT_OK`, 0 un extra absent ou d'un
     * autre type (`getIntExtra`) : ni l'un ni l'autre n'est une erreur à montrer.
     */
    @Test
    fun `erreur d'Android - code hors plage, rien, meme avec un statut HTTP valide`() {
        listOf(null, 0, -1, 100, 403, Int.MIN_VALUE, Int.MAX_VALUE).forEach { code ->
            assertThat(MmsFailureNotifier.ErreurAndroid.valide(MmsFailureNotifier.Reason.DOWNLOAD_FAILED, code, 403))
                .isNull()
        }
    }

    @Test
    fun `erreur d'Android - statut HTTP hors plage, seul le code reste`() {
        listOf(0, -1, 99, 600, 1_000, Int.MIN_VALUE, Int.MAX_VALUE).forEach { http ->
            assertThat(MmsFailureNotifier.ErreurAndroid.valide(MmsFailureNotifier.Reason.DOWNLOAD_FAILED, 4, http))
                .isEqualTo(MmsFailureNotifier.ErreurAndroid(4))
        }
    }

    /** Seul un téléchargement manqué naît d'un code de résultat d'Android. */
    @Test
    fun `erreur d'Android - tout autre motif, rien`() {
        MmsFailureNotifier.Reason.entries
            .filter { it != MmsFailureNotifier.Reason.DOWNLOAD_FAILED }
            .forEach { reason ->
                assertThat(MmsFailureNotifier.ErreurAndroid.valide(reason, 4, 403)).isNull()
            }
    }
}
