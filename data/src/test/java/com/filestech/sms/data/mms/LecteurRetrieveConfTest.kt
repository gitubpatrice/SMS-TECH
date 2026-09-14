package com.filestech.sms.data.mms

import com.filestech.sms.pdu.EncodedStringValue
import com.filestech.sms.pdu.PduBody
import com.filestech.sms.pdu.PduPart
import com.filestech.sms.pdu.RetrieveConf
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.28.9 (F17) — la lecture d'un `RetrieveConf`, sortie du receveur pour être partagée avec la reprise.
 *
 * La liste des parties retenues fait foi pour la complétude d'un message repris : une partie comptée à
 * tort (la légende, la mise en page) ferait compléter en boucle, une partie oubliée (la carte de visite)
 * ferait déclarer complet un message amputé. Le `RetrieveConf` est construit par ses setters, comme le
 * parseur le remplit.
 */
class LecteurRetrieveConfTest {

    private val maintenant = 1_726_000_000_000L

    private fun partie(mime: ByteArray, donnees: ByteArray, charset: Int? = null) = PduPart().apply {
        setContentType(mime)
        setData(donnees)
        if (charset != null) setCharset(charset)
    }

    private fun partie(mime: String, donnees: ByteArray, charset: Int? = null) =
        partie(mime.toByteArray(Charsets.US_ASCII), donnees, charset)

    private fun conf(
        vararg parties: PduPart,
        de: String? = "+33600000001/TYPE=PLMN",
        date: Long = 1_700_000_000L,
        sujet: String? = null,
    ) = RetrieveConf().apply {
        // En UTF-8, comme un vrai PDU : `EncodedStringValue(String)` encode en ISO-8859-1, où un caractère
        // invisible devient `?` avant même d'atteindre le nettoyage que le test vise.
        if (de != null) setFrom(EncodedStringValue(UTF8_MIB, de.toByteArray(Charsets.UTF_8)))
        setDate(date)
        if (sujet != null) setSubject(EncodedStringValue(sujet))
        setBody(PduBody().apply { parties.forEach { addPart(it) } })
    }

    @Test
    fun `les parties retenues ecartent legende et mise en page, gardent la carte de visite, dans l'ordre`() {
        val contenu = LecteurRetrieveConf.lire(
            conf(
                partie("application/smil", "<smil/>".toByteArray()),
                partie("text/plain", "Bonjour".toByteArray(), charset = UTF8_MIB),
                partie("image/jpeg", byteArrayOf(1, 2, 3)),
                partie("text/x-vcard", byteArrayOf(4, 5)),
            ),
            indiceExpediteur = null,
            maintenant = maintenant,
        )

        assertThat(contenu.parties.map { it.mime }).containsExactly("image/jpeg", "text/x-vcard").inOrder()
        assertThat(contenu.legende).isEqualTo("Bonjour")
    }

    @Test
    fun `une partie vide n'est pas retenue`() {
        val contenu = LecteurRetrieveConf.lire(
            conf(partie("image/jpeg", ByteArray(0)), partie("image/png", byteArrayOf(9))),
            indiceExpediteur = null,
            maintenant = maintenant,
        )

        assertThat(contenu.parties.map { it.mime }).containsExactly("image/png")
    }

    @Test
    fun `le type perd le NUL final des chaines WAP, ses parametres et ses majuscules`() {
        val brut = "Image/JPEG; name=photo".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

        assertThat(LecteurRetrieveConf.decoderMime(brut)).isEqualTo("image/jpeg")
        assertThat(LecteurRetrieveConf.decoderMime(byteArrayOf(0))).isNull()
        assertThat(LecteurRetrieveConf.decoderMime(null)).isNull()
    }

    @Test
    fun `l'expediteur perd le suffixe de passerelle et les caracteres invisibles`() {
        val espaceSansChasse = Char(0x200B)
        val contenu = LecteurRetrieveConf.lire(
            conf(de = "+336000000${espaceSansChasse}01/TYPE=PLMN"),
            indiceExpediteur = null,
            maintenant = maintenant,
        )

        assertThat(contenu.expediteur).isEqualTo("+33600000001")
    }

    @Test
    fun `sans From, l'indice de la notification sert, nettoye lui aussi`() {
        val contenu = LecteurRetrieveConf.lire(conf(de = null), indiceExpediteur = "+33600000002/TYPE=PLMN", maintenant)

        assertThat(contenu.expediteur).isEqualTo("+33600000002")
    }

    @Test
    fun `les destinataires et les copies sont nettoyes comme l'expediteur`() {
        val avecCopies = conf().apply {
            addTo(EncodedStringValue("+33600000002/TYPE=PLMN"))
            addCc(EncodedStringValue("+33600000003/TYPE=PLMN"))
        }

        val contenu = LecteurRetrieveConf.lire(avecCopies, indiceExpediteur = null, maintenant = maintenant)

        assertThat(contenu.destinataires).containsExactly("+33600000002")
        assertThat(contenu.copies).containsExactly("+33600000003")
    }

    @Test
    fun `la date du PDU est en secondes, et absente c'est l'heure donnee`() {
        assertThat(LecteurRetrieveConf.lire(conf(date = 1_700_000_000L), null, maintenant).date)
            .isEqualTo(1_700_000_000_000L)
        assertThat(LecteurRetrieveConf.lire(conf(date = 0L), null, maintenant).date).isEqualTo(maintenant)
    }

    @Test
    fun `le libelle d'apercu est la legende, sinon le sujet, sinon le type de la premiere partie`() {
        val avecLegende = conf(
            partie("text/plain", "salut".toByteArray()),
            partie("image/jpeg", byteArrayOf(1)),
            sujet = "Sujet",
        )
        val avecSujet = conf(partie("image/jpeg", byteArrayOf(1)), sujet = "Sujet")
        val sansRien = conf(partie("audio/amr", byteArrayOf(1)))

        assertThat(LecteurRetrieveConf.lire(avecLegende, null, maintenant).libelleApercu).isEqualTo("salut")
        assertThat(LecteurRetrieveConf.lire(avecSujet, null, maintenant).libelleApercu).isEqualTo("Sujet")
        assertThat(LecteurRetrieveConf.lire(sansRien, null, maintenant).libelleApercu).isEqualTo("🎤")
        assertThat(LecteurRetrieveConf.lire(conf(), null, maintenant).libelleApercu).isEqualTo("[MMS]")
    }

    /** Le jeu « quelconque » du WAP (MIBenum 0) n'est pas un jeu Java : la légende se lit en UTF-8. */
    @Test
    fun `une legende en jeu de caracteres quelconque se lit en UTF-8`() {
        val contenu = LecteurRetrieveConf.lire(
            conf(partie("text/plain", "été".toByteArray(Charsets.UTF_8), charset = 0)),
            indiceExpediteur = null,
            maintenant = maintenant,
        )

        assertThat(contenu.legende).isEqualTo("été")
    }

    private companion object {
        /** MIBenum d'UTF-8. */
        const val UTF8_MIB = 106
    }
}
