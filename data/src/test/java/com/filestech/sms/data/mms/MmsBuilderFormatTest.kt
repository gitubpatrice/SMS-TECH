package com.filestech.sms.data.mms

import android.content.Context
import com.filestech.sms.domain.mms.MmsAttachment
import com.filestech.sms.pdu.CharacterSets
import com.filestech.sms.pdu.EncodedStringValue
import com.filestech.sms.pdu.PduBody
import com.filestech.sms.pdu.PduComposer
import com.filestech.sms.pdu.PduParser
import com.filestech.sms.pdu.SendReq
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Issue #53 : un opérateur refusait nos MMS avec `Error-permanent-message-format-corrupt` (0xE2). Notre
 * `PduComposer` est une réécriture de celui d'AOSP, et elle en avait divergé sans que rien ne le voie :
 * le message partait sans `start` ni `type`, les parties sans charset, un code de nom que seul WSP 1.4
 * connaît. Tant qu'un opérateur tolère l'écart, tout paraît marcher.
 *
 * Les références (`src/test/resources/.../mms-reference/`) sont les octets écrits par le COMPOSEUR D'AOSP
 * lui-même (`com.google.android.mms.pdu`, sources du SDK android-36.1), pour le même message, avec les
 * champs de `MmsUtils.createSendReq` d'AOSP Messaging et un Transaction-Id et une Date figés. Ce test
 * n'exige donc pas ce que nous croyons juste, il exige ce que le code d'Android écrit.
 */
class MmsBuilderFormatTest {

    @TempDir
    lateinit var dossier: File

    private val builder = MmsBuilder(mockk<Context>(relaxed = true))

    private fun fichier(nom: String) = File(dossier, nom).apply { writeBytes(DONNEES) }

    private fun reference(nom: String): String =
        checkNotNull(javaClass.getResourceAsStream("/mms-reference/$nom.hex")) { "référence absente : $nom" }
            .use { it.readBytes().decodeToString().trim() }

    private fun hexa(octets: ByteArray) = octets.joinToString("") { "%02x".format(it) }

    /** Transaction-Id et Date changent à chaque envoi : ils reprennent les valeurs de la référence. */
    private fun fige(pdu: ByteArray): ByteArray {
        assertThat(pdu.take(3)).containsExactly(0x8C.toByte(), 0x80.toByte(), 0x98.toByte()).inOrder()
        val finTransaction = (3 until pdu.size).first { pdu[it] == 0.toByte() }
        val versionEtDate = pdu.sliceArray(finTransaction + 1 until finTransaction + 4)
        assertThat(versionEtDate.toList()).containsExactly(0x8D.toByte(), 0x92.toByte(), 0x85.toByte()).inOrder()
        val debutDate = finTransaction + 5
        val finDate = debutDate + pdu[finTransaction + 4]
        return pdu.sliceArray(0 until 3) + "T0".toByteArray() + byteArrayOf(0) + versionEtDate +
            byteArrayOf(4, 0x60, 0, 0, 0) + pdu.sliceArray(finDate until pdu.size)
    }

    private fun photo() = MmsAttachment(fichier("IMG_1.jpg"), "image/jpeg", MmsAttachment.Kind.IMAGE)

    @Test
    fun `une photo seule sort octet pour octet comme l'ecrit le composeur d'AOSP`() {
        val pdu = builder.buildMultipartSendReq(listOf(photo()), null, listOf(DESTINATAIRE))

        assertThat(hexa(fige(checkNotNull(pdu)))).isEqualTo(reference("photo"))
    }

    @Test
    fun `une photo et sa legende accentuee sortent octet pour octet comme l'ecrit le composeur d'AOSP`() {
        val pdu = builder.buildMultipartSendReq(listOf(photo()), "Café", listOf(DESTINATAIRE))

        assertThat(hexa(fige(checkNotNull(pdu)))).isEqualTo(reference("legende"))
    }

    @Test
    fun `un message vocal, de type hors table WSP, sort octet pour octet comme l'ecrit le composeur d'AOSP`() {
        val pdu = builder.buildVoiceSendReq(fichier("voice.m4a"), "audio/mp4", listOf(DESTINATAIRE))

        assertThat(hexa(fige(checkNotNull(pdu)))).isEqualTo(reference("vocal"))
    }

    @Test
    fun `un texte seul sort octet pour octet comme l'ecrit le composeur d'AOSP`() {
        val pdu = builder.buildMultipartSendReq(emptyList(), "Bonjour", listOf(DESTINATAIRE))

        assertThat(hexa(fige(checkNotNull(pdu)))).isEqualTo(reference("texte"))
    }

    private fun utf8(texte: String) = EncodedStringValue(CharacterSets.UTF_8, texte.toByteArray())

    /** Sans passer par `MmsBuilder`, qui n'atteint pas ces deux chemins aujourd'hui. */
    private fun requeteSansPartie(expediteur: String?) = SendReq().apply {
        setTransactionId("T0".toByteArray())
        if (expediteur != null) setFrom(utf8(expediteur))
        addTo(utf8("$DESTINATAIRE/TYPE=PLMN"))
        setDate(0x60000000L)
        setBody(PduBody())
    }

    @Test
    fun `un message sans partie sort octet pour octet comme l'ecrit le composeur d'AOSP`() {
        val pdu = PduComposer(null, requeteSansPartie(expediteur = null)).make()

        assertThat(hexa(checkNotNull(pdu))).isEqualTo(reference("vide"))
    }

    @Test
    fun `un expediteur renseigne sort octet pour octet comme l'ecrit le composeur d'AOSP`() {
        val pdu = PduComposer(null, requeteSansPartie(expediteur = "$DESTINATAIRE/TYPE=PLMN")).make()

        assertThat(hexa(checkNotNull(pdu))).isEqualTo(reference("expediteur"))
    }

    /** Notre propre parseur lit aussi ce format : c'est lui qui recevra les messages d'un autre SMS Tech. */
    @Test
    fun `notre parseur relit les parties et le charset de la legende`() {
        val pdu = checkNotNull(builder.buildMultipartSendReq(listOf(photo()), "Café", listOf(DESTINATAIRE)))

        val corps = checkNotNull((PduParser(pdu).parse() as SendReq).body)

        assertThat(corps.partsNum).isEqualTo(3)
        assertThat(corps.getPart(0).contentType.decodeToString()).isEqualTo("application/smil")
        assertThat(corps.getPart(1).charset).isEqualTo(CharacterSets.UTF_8)
        assertThat(corps.getPart(1).contentId.decodeToString()).isEqualTo("text")
        assertThat(corps.getPart(1).data.decodeToString()).isEqualTo("Café")
        assertThat(corps.getPart(2).contentType.decodeToString()).isEqualTo("image/jpeg")
        assertThat(corps.getPart(2).data.toList()).containsExactlyElementsIn(DONNEES.toList()).inOrder()
    }

    private companion object {
        const val DESTINATAIRE = "+33612345678"
        val DONNEES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)
    }
}
