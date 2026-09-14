package com.filestech.sms.data.sms

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v1.28.9 (audit de cohérence du 2026-09-14, C2) — **un MMS qui ne porte qu'une carte de visite est importé.**
 *
 * L'import classait toute partie `text/…` en texte et lisait sa colonne `text`, vide pour une vCard que le
 * fournisseur range dans un fichier : le MMS n'avait ni texte ni pièce, et `flushPendingWithBatchedParts` le
 * sautait. Le test écrit un vrai MMS dans `content://mms` et le fait relire par l'import : un faux fournisseur
 * répondrait ce qu'on lui fait dire.
 *
 * Prérequis : le rôle SMS, comme `MmsRowIdentityTest` — la précondition TENTE l'écriture.
 */
@RunWith(AndroidJUnit4::class)
class MmsImportVcardTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val resolver get() = context.contentResolver

    private val ecrits = mutableListOf<Uri>()

    @After
    fun tearDown() {
        val survivants = ecrits.filter { uri ->
            runCatching { resolver.delete(uri, null, null) }
            resolver.query(uri, arrayOf(Telephony.Mms._ID), null, null, null).use { it != null && it.moveToFirst() }
        }
        assertThat(survivants).isEmpty()
    }

    @Test
    fun unMmsNePortantQuUneCarteDeVisiteEstImporteAvecSaPiece() {
        val id = insererMmsOuIgnorer()
        val partie = insererPartie(id, ContentValues().apply {
            put("ct", VCARD)
            put("cl", "contact.vcf")
            put("name", "contact.vcf")
        })
        resolver.openOutputStream(partie)!!.use { it.write(CARTE.toByteArray(Charsets.UTF_8)) }

        val ligne = importer(id)

        assertThat(ligne).isNotNull()
        assertThat(ligne!!.textBody).isEmpty()
        assertThat(ligne.attachments.map { it.contentType }).containsExactly(VCARD)
    }

    /** Contrôle : la légende reste du texte, et n'est pas prise pour une pièce jointe. */
    @Test
    fun uneLegendeResteDuTexteAupresDeLaCarte() {
        val id = insererMmsOuIgnorer()
        insererPartie(id, ContentValues().apply {
            put("ct", "text/plain")
            put("text", LEGENDE)
        })
        val carte = insererPartie(id, ContentValues().apply { put("ct", VCARD) })
        resolver.openOutputStream(carte)!!.use { it.write(CARTE.toByteArray(Charsets.UTF_8)) }

        val ligne = importer(id)

        assertThat(ligne).isNotNull()
        assertThat(ligne!!.textBody).isEqualTo(LEGENDE)
        assertThat(ligne.attachments.map { it.contentType }).containsExactly(VCARD)
    }

    private fun importer(id: Long): TelephonyReader.MmsImportRow? {
        val lues = mutableListOf<TelephonyReader.MmsImportRow>()
        runBlocking { TelephonyReader(context).readMmsBatched(pageSize = 200) { lues += it } }
        return lues.firstOrNull { it.telephonyId == id }
    }

    private fun insererMmsOuIgnorer(): Long {
        val dateSec = System.currentTimeMillis() / 1000L
        val cv = ContentValues().apply {
            put(Telephony.Mms.DATE, dateSec)
            put(Telephony.Mms.DATE_SENT, dateSec)
            put(Telephony.Mms.MESSAGE_BOX, MMS_BOITE_RECEPTION)
            put(Telephony.Mms.MESSAGE_TYPE, MMS_TYPE_RETRIEVE_CONF)
            put(Telephony.Mms.MMS_VERSION, MMS_VERSION_1_0)
            put(Telephony.Mms.READ, 1)
            put(Telephony.Mms.SEEN, 1)
            put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related")
        }
        val tentative = runCatching { resolver.insert(Telephony.Mms.CONTENT_URI, cv) }
        val insere = tentative.getOrNull()
        assumeTrue(
            "ecriture dans content://mms refusee — " +
                (tentative.exceptionOrNull()?.toString() ?: "insert a rendu null, sans exception"),
            insere != null,
        )
        ecrits += insere!!
        val id = insere.lastPathSegment!!.toLong()
        val addr = ContentValues().apply {
            put("address", EXPEDITEUR)
            put("type", PDU_HEADER_FROM)
            put("charset", CHARSET_UTF_8)
        }
        assumeTrue(
            "ecriture dans content://mms/<id>/addr refusee",
            runCatching { resolver.insert(Uri.parse("content://mms/$id/addr"), addr) }.getOrNull() != null,
        )
        return id
    }

    private fun insererPartie(id: Long, valeurs: ContentValues): Uri {
        val uri = resolver.insert(Uri.parse("content://mms/$id/part"), valeurs)
        assertThat(uri).isNotNull()
        return uri!!
    }

    private companion object {
        const val EXPEDITEUR = "+33600000044"
        const val VCARD = "text/x-vcard"
        const val LEGENDE = "Voici le contact"
        const val CARTE = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Test Import\r\nTEL:+33600000045\r\nEND:VCARD\r\n"

        /** Les constantes du module `:data` sont `internal` — hors de portée ici. */
        const val MMS_BOITE_RECEPTION = 1
        const val MMS_TYPE_RETRIEVE_CONF = 132
        const val MMS_VERSION_1_0 = 0x10
        const val PDU_HEADER_FROM = 137
        const val CHARSET_UTF_8 = 106
    }
}
