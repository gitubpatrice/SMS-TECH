package com.filestech.sms.data.sms

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * v1.29.2 — **le compte et le découpage d'un long texte Unicode, quand Android refuse de les faire.**
 *
 * Trace d'un utilisateur (Android 17, GrapheneOS) : `SecurityException: getGroupIdLevel1` depuis
 * `SmsMessage.calculateLength`, au collage d'un long texte ; reproduite sous Android 17, permission Téléphone
 * refusée. Ici, la règle de repli et le fait qu'elle ne s'applique QUE sur ce refus.
 */
class DecoupageUnicodeTest {

    @Test
    fun `jusqu'a 70 unites, un seul SMS`() {
        val texte = "ê".repeat(70)
        assertThat(decouperEnUcs2(texte)).containsExactly(texte)
        assertThat(statsUcs2(texte)).isEqualTo(SmsSegmentCounter.Stats(70, 1, 0, true))
    }

    @Test
    fun `au-dela de 70 unites, des parties de 67`() {
        assertThat(decouperEnUcs2("ê".repeat(71)).map { it.length }).containsExactly(67, 4).inOrder()
        assertThat(decouperEnUcs2("ê".repeat(134)).map { it.length }).containsExactly(67, 67).inOrder()
        assertThat(decouperEnUcs2("ê".repeat(135)).map { it.length }).containsExactly(67, 67, 1).inOrder()
    }

    @Test
    fun `meme compte que le systeme, mesure sous Android 17 sur l'emulateur`() {
        // Le champ affichait « 241 / 268 • 4 seg. » puis « 334 / 335 • 5 seg. » : longueur, longueur + reste,
        // nombre de SMS. Le repli doit annoncer la même chose.
        assertThat(statsUcs2("ê".repeat(241))).isEqualTo(SmsSegmentCounter.Stats(241, 4, 27, true))
        assertThat(statsUcs2("ê".repeat(334))).isEqualTo(SmsSegmentCounter.Stats(334, 5, 1, true))
    }

    @Test
    fun `un emoji a cheval sur la limite passe entier dans la partie suivante`() {
        val texte = "ê".repeat(66) + "😀" + "ê".repeat(10)
        val parties = decouperEnUcs2(texte)
        assertThat(parties.first().length).isEqualTo(66)
        assertThat(parties[1]).startsWith("😀")
        assertThat(parties.joinToString("")).isEqualTo(texte)
        for (partie in parties) {
            assertThat(Character.isHighSurrogate(partie.last())).isFalse()
            assertThat(partie.length).isAtMost(UCS2_UNITES_PAR_PARTIE)
        }
    }

    @Test
    fun `une lettre et son accent combinant ne sont jamais separes`() {
        // « e » + U+0301 à cheval sur la limite : aucune paire de substitution ici, seule la frontière
        // de caractère empêche la coupure. Un découpage qui ne regarderait que les paires couperait à 67.
        val texte = "ê".repeat(66) + "é" + "ê".repeat(10)
        val parties = decouperEnUcs2(texte)
        assertThat(parties.first().length).isEqualTo(66)
        assertThat(parties[1]).startsWith("é")
        assertThat(parties.joinToString("")).isEqualTo(texte)
    }

    @Test
    fun `aucun texte n'est perdu ni double par le decoupage`() {
        for (texte in listOf("ê", "Ça marche, à très bientôt ’".repeat(9), "😀".repeat(80), "é😀".repeat(50))) {
            assertThat(decouperEnUcs2(texte).joinToString("")).isEqualTo(texte)
        }
    }

    @Test
    fun `le compteur garde le calcul du systeme quand il repond`() {
        val stats = SmsSegmentCounter.compterSegments("ê".repeat(200)) { intArrayOf(1, 10, 150, 1) }
        assertThat(stats).isEqualTo(SmsSegmentCounter.Stats(10, 1, 150, false))
    }

    @Test
    fun `le compteur se replie sur le refus du GID1 au lieu de fermer l'application`() {
        val texte = "ê".repeat(241)
        val stats = SmsSegmentCounter.compterSegments(texte) { throw SecurityException("getGroupIdLevel1") }
        assertThat(stats).isEqualTo(statsUcs2(texte))
    }

    @Test
    fun `le compteur ne rattrape aucune autre exception`() {
        assertThrows<IllegalStateException> {
            SmsSegmentCounter.compterSegments("ê".repeat(241)) { throw IllegalStateException("autre") }
        }
    }

    @Test
    fun `l'envoi garde le decoupage du systeme quand il repond`() {
        val parties = partiesAEnvoyer("ê".repeat(200)) { arrayListOf("a", "b") }
        assertThat(parties).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `l'envoi decoupe lui-meme sur le refus du GID1`() {
        val texte = "ê".repeat(135)
        val parties = partiesAEnvoyer(texte) { throw SecurityException("getGroupIdLevel1") }
        assertThat(parties).containsExactlyElementsIn(decouperEnUcs2(texte)).inOrder()
    }

    @Test
    fun `l'envoi ne rattrape aucune autre exception`() {
        assertThrows<IllegalArgumentException> {
            partiesAEnvoyer("") { throw IllegalArgumentException("texte vide") }
        }
    }
}
