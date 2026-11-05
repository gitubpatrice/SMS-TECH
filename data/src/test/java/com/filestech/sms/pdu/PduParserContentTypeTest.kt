package com.filestech.sms.pdu

import com.filestech.sms.data.mms.LecteurRetrieveConf
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * La lecture du Content-Type d'une partie reçue, sur des octets écrits à la main : ces trois défauts ne se
 * voient qu'à la forme que choisit l'opérateur ou le téléphone d'en face, jamais avec nos propres PDU.
 *
 * Trouvés le 2026-11-05 en relisant nos messages sortants (issue #53) :
 *  - le paramètre `charset` était lu comme une chaîne jusqu'au prochain octet nul : son octet de valeur et
 *    les en-têtes suivants disparaissaient, et le charset déclaré n'était jamais retenu ;
 *  - le `type` du message restait en mémoire et remplaçait le type d'une partie en forme courte, si bien
 *    qu'une photo passait pour la mise en page et disparaissait du message.
 */
class PduParserContentTypeTest {

    /** `type=application/smil` sur le message, comme l'écrivent AOSP et, désormais, SMS Tech. */
    private val typeDuMessage = forme(
        byteArrayOf(0xB3.toByte(), 0x89.toByte()) + "application/smil".toByteArray() + byteArrayOf(0),
    )

    /** Value-length (court) puis la valeur. */
    private fun forme(valeur: ByteArray) = byteArrayOf(valeur.size.toByte()) + valeur

    private fun partie(entetes: ByteArray, donnees: ByteArray) =
        byteArrayOf(entetes.size.toByte(), donnees.size.toByte()) + entetes + donnees

    /** Un m-retrieve.conf : type de message, Content-Type en dernier, nombre de parties, parties. */
    private fun corps(vararg parties: ByteArray): PduBody {
        val pdu = byteArrayOf(0x8C.toByte(), 0x84.toByte(), 0x84.toByte()) + typeDuMessage +
            byteArrayOf(parties.size.toByte()) + parties.fold(ByteArray(0)) { a, b -> a + b }
        return checkNotNull((PduParser(pdu).parse() as RetrieveConf).body)
    }

    private fun texte(parametres: ByteArray, donnees: ByteArray, suite: ByteArray = ByteArray(0)) =
        partie(forme(byteArrayOf(0x83.toByte()) + parametres) + suite, donnees)

    private val contentIdT = byteArrayOf(0xC0.toByte(), 0x22) + "<t>".toByteArray() + byteArrayOf(0)

    @Test
    fun `une photo en forme courte garde son type malgre le type du message`() {
        val corps = corps(partie(byteArrayOf(0x9E.toByte()), byteArrayOf(1, 2, 3)))

        assertThat(corps.getPart(0).contentType.decodeToString()).isEqualTo("image/jpeg")
    }

    @Test
    fun `un type de partie en texte court garde son type malgre le type du message`() {
        val corps = corps(partie("image/heic".toByteArray() + byteArrayOf(0), byteArrayOf(1, 2, 3)))

        assertThat(corps.getPart(0).contentType.decodeToString()).isEqualTo("image/heic")
    }

    @Test
    fun `le charset est retenu et l'en-tete qui le suit reste lu`() {
        val corps = corps(
            texte(byteArrayOf(0x81.toByte(), 0xEA.toByte()), "é".toByteArray(), suite = contentIdT),
        )

        assertThat(corps.getPart(0).charset).isEqualTo(CharacterSets.UTF_8)
        assertThat(corps.getPart(0).contentId.decodeToString()).isEqualTo("t")
    }

    @Test
    fun `une legende declaree ISO-8859-1 se lit en ISO-8859-1`() {
        val corps = corps(texte(byteArrayOf(0x81.toByte(), 0x84.toByte()), "Café".toByteArray(Charsets.ISO_8859_1)))

        assertThat(LecteurRetrieveConf.premiereLegende(corps)).isEqualTo("Café")
    }

    @Test
    fun `un charset en entier long, ici UTF-16, se lit`() {
        val corps = corps(
            texte(byteArrayOf(0x81.toByte(), 0x02, 0x03, 0xF7.toByte()), "Café".toByteArray(Charsets.UTF_16)),
        )

        assertThat(corps.getPart(0).charset).isEqualTo(CharacterSets.UTF_16)
        assertThat(LecteurRetrieveConf.premiereLegende(corps)).isEqualTo("Café")
    }

    @Test
    fun `un charset ecrit en toutes lettres se lit`() {
        val nom = byteArrayOf(0x81.toByte()) + "utf-8".toByteArray() + byteArrayOf(0)

        assertThat(corps(texte(nom, "é".toByteArray())).getPart(0).charset).isEqualTo(CharacterSets.UTF_8)
    }

    @Test
    fun `sans charset utilisable la legende se lit en UTF-8`() {
        val quelconque = texte(byteArrayOf(0x81.toByte(), 0x80.toByte()), "Café".toByteArray())
        val nomInconnu = byteArrayOf(0x81.toByte()) + "x-inconnu".toByteArray() + byteArrayOf(0)
        val inconnu = texte(nomInconnu, "Café".toByteArray())

        assertThat(corps(quelconque).getPart(0).charset).isEqualTo(CharacterSets.ANY_CHARSET)
        assertThat(LecteurRetrieveConf.premiereLegende(corps(quelconque))).isEqualTo("Café")
        assertThat(corps(inconnu).getPart(0).charset).isEqualTo(CharacterSets.ANY_CHARSET)
        assertThat(LecteurRetrieveConf.premiereLegende(corps(inconnu))).isEqualTo("Café")
    }

    @Test
    fun `un parametre sans fin de chaine ne deborde pas sur l'en-tete suivant`() {
        val nomSansFin = byteArrayOf(0x85.toByte()) + "nom".toByteArray()

        val corps = corps(texte(nomSansFin, "x".toByteArray(), suite = contentIdT))

        assertThat(corps.getPart(0).contentId.decodeToString()).isEqualTo("t")
    }

    /** `level` (0x82) vaut un entier court : le lire comme une chaîne avalait le charset qui le suit. */
    @Test
    fun `un parametre de valeur entiere ne cache pas le charset qui le suit`() {
        val parametres = byteArrayOf(0x82.toByte(), 0x81.toByte(), 0x81.toByte(), 0x84.toByte())

        val corps = corps(texte(parametres, "Café".toByteArray(Charsets.ISO_8859_1)))

        assertThat(corps.getPart(0).charset).isEqualTo(CharacterSets.ISO_8859_1)
        assertThat(LecteurRetrieveConf.premiereLegende(corps)).isEqualTo("Café")
    }

    @Test
    fun `un nom de parametre ecrit en toutes lettres est compris`() {
        val autre = "foo".toByteArray() + byteArrayOf(0) + "bar".toByteArray() + byteArrayOf(0)
        val charset = "charset".toByteArray() + byteArrayOf(0, 0x84.toByte())

        val corps = corps(texte(autre + charset, "Café".toByteArray(Charsets.ISO_8859_1)))

        assertThat(corps.getPart(0).charset).isEqualTo(CharacterSets.ISO_8859_1)
    }

    @Test
    fun `un charset en texte cite se lit`() {
        val cite = byteArrayOf(0x81.toByte(), 0x7F) + "utf-8".toByteArray() + byteArrayOf(0)

        assertThat(corps(texte(cite, "é".toByteArray())).getPart(0).charset).isEqualTo(CharacterSets.UTF_8)
    }

    @Test
    fun `un charset tronque se lit comme quelconque`() {
        val corps = corps(texte(byteArrayOf(0x81.toByte()), "Café".toByteArray()))

        assertThat(corps.getPart(0).charset).isEqualTo(CharacterSets.ANY_CHARSET)
        assertThat(LecteurRetrieveConf.premiereLegende(corps)).isEqualTo("Café")
    }

    @Test
    fun `une legende accentuee etiquetee us-ascii a tort garde ses accents`() {
        val corps = corps(texte(byteArrayOf(0x81.toByte(), 0x83.toByte()), "Café".toByteArray()))

        assertThat(corps.getPart(0).charset).isEqualTo(CharacterSets.US_ASCII)
        assertThat(LecteurRetrieveConf.premiereLegende(corps)).isEqualTo("Café")
    }

    @Test
    fun `le charset d'une partie ne passe pas a la partie suivante`() {
        val corps = corps(
            texte(byteArrayOf(0x81.toByte(), 0xEA.toByte()), "é".toByteArray()),
            partie(byteArrayOf(0x9E.toByte()), byteArrayOf(1, 2, 3)),
        )

        assertThat(corps.getPart(0).charset).isEqualTo(CharacterSets.UTF_8)
        assertThat(corps.getPart(1).charset).isEqualTo(CharacterSets.ANY_CHARSET)
    }
}
