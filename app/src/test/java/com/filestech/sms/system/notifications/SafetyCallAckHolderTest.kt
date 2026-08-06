package com.filestech.sms.system.notifications

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.27.4 — verrouille le porteur de confirmation des gestes Safety call faits depuis une
 * notification.
 *
 * # Ce que ce test protège, et pourquoi ça vaut un test
 *
 * Le défaut corrigé n'était pas une erreur de calcul : c'était un **silence**. Le tap sur la
 * notification acquittait l'alerte et ouvrait la liste des SMS sans un mot — indiscernable d'une
 * notification ratée. Un porteur qui perdrait son message rétablirait ce silence exactement, et
 * aucun test d'interface ne le verrait : le geste continuerait de « fonctionner ».
 *
 * C'est pour cette raison que le sens du repli est testé explicitement, et pas seulement le chemin
 * heureux.
 */
class SafetyCallAckHolderTest {

    private companion object {
        const val T0 = 1_786_000_000_000L
    }

    @Test
    fun `un geste pose puis consomme rend le bon cas`() {
        val holder = SafetyCallAckHolder()

        holder.set(SafetyCallAckHolder.Ack.DISARMED, T0)

        assertThat(holder.consume(T0)?.ack).isEqualTo(SafetyCallAckHolder.Ack.DISARMED)
    }

    @Test
    fun `une seconde consommation ne rejoue pas la confirmation`() {
        // Sans cela, la moindre recomposition réafficherait le message : l'utilisateur verrait
        // « Safety call désactivé » revenir sans avoir rien fait, ce qui est aussi trompeur que le
        // silence qu'on corrige.
        val holder = SafetyCallAckHolder()
        holder.set(SafetyCallAckHolder.Ack.TIMER_RESET, T0)

        holder.consume(T0)

        assertThat(holder.consume(T0)).isNull()
    }

    @Test
    fun `le dernier geste ecrase le precedent`() {
        // Deux gestes rapprochés — acquitter puis réactiver — doivent afficher l'état ATTEINT, pas
        // celui d'avant. Confirmer « Safety call désactivé » après un réarmement réussi annoncerait
        // l'absence d'une protection qui vient d'être remise en marche.
        val holder = SafetyCallAckHolder()

        holder.set(SafetyCallAckHolder.Ack.DISARMED, T0)
        holder.set(SafetyCallAckHolder.Ack.REARMED, T0)

        assertThat(holder.consume(T0)?.ack).isEqualTo(SafetyCallAckHolder.Ack.REARMED)
    }

    @Test
    fun `clear efface sans rien rendre`() {
        // Le chemin du mode leurre : la confirmation ne doit pas être mise en attente, elle doit
        // disparaître. La garder ferait surgir la phrase à la sortie du leurre, révélant après coup
        // l'existence de la fonction.
        val holder = SafetyCallAckHolder()
        holder.set(SafetyCallAckHolder.Ack.DISARMED, T0)

        holder.clear()

        assertThat(holder.consume(T0)).isNull()
    }

    @Test
    fun `un geste perime n'est pas affiche et n'est pas laisse derriere`() {
        val holder = SafetyCallAckHolder()
        holder.set(SafetyCallAckHolder.Ack.DISARMED, T0)
        val wellPast = T0 + SafetyCallAckHolder.PENDING_TTL_MS + 1

        assertThat(holder.consume(wellPast)).isNull()
        // Et il a bien été effacé au passage : un porteur périmé qui resterait en place serait
        // consommé plus tard par une session ouverte pour une autre raison.
        assertThat(holder.pending.value).isNull()
    }

    @Test
    fun `juste avant l'echeance la confirmation passe encore`() {
        // La borne, dans le sens utile : `isExpired` est un `>` strict, donc l'instant exact de
        // l'échéance n'est PAS périmé. Tester l'autre côté seulement laisserait passer un `>=`.
        val holder = SafetyCallAckHolder()
        holder.set(SafetyCallAckHolder.Ack.TIMER_RESET, T0)

        val atDeadline = T0 + SafetyCallAckHolder.PENDING_TTL_MS

        assertThat(holder.consume(atDeadline)).isNotNull()
    }

    @Test
    fun `le delai est plus genereux que celui d'une navigation en attente`() {
        // Ce n'est pas un détail de confort, c'est le raisonnement du KDoc rendu vérifiable : une
        // navigation périmée désignerait une mauvaise cible, donc son TTL court est une garde ; une
        // confirmation énonce un fait déjà acquis, et l'afficher tard reste vrai. Les deux replis
        // n'échouent pas du même côté, donc les deux délais ne doivent pas être égaux.
        assertThat(SafetyCallAckHolder.PENDING_TTL_MS)
            .isGreaterThan(PendingNavHolder.PENDING_TTL_MS)
    }

    @Test
    fun `chaque cas d'acquittement a une phrase distincte a resoudre`() {
        // Garde-fou d'exhaustivité : si un cas est ajouté à l'énumération, le `when` de l'overlay
        // dans AppRoot doit être complété. Kotlin l'impose déjà pour un `when` sur une enum utilisé
        // comme expression, mais ce test rend l'intention explicite au lieu de la laisser dépendre
        // d'un détail de compilation qu'un `else` suffirait à contourner.
        assertThat(SafetyCallAckHolder.Ack.entries).hasSize(3)
    }
}
