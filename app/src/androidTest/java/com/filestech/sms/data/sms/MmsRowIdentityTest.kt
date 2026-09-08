package com.filestech.sms.data.sms

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v1.28.1 — l'identite d'un MMS, la ou elle est la plus faible.
 *
 * # Le trou, cherche et non signale
 *
 * Ni le testeur externe ni la relecture GPT n'avaient de mesure ici. Cote `content://sms`,
 * l'identite compare date, corps, sens et adresse. Cote `content://mms`, la table designee par
 * l'URI ne porte NI corps NI adresse — ils vivent dans `part` et `addr` — si bien que la
 * comparaison se reduisait a date + sens. **Deux MMS recus a moins d'une minute d'intervalle
 * etaient donc indiscernables**, et une liaison venue d'un autre telephone pouvait faire
 * supprimer le MMS de quelqu'un d'autre.
 *
 * L'adresse est desormais relue dans `content://mms/<id>/addr`. Une requete de plus sur un chemin
 * destructeur et rare : c'est peu cher pour la seule chose qui separe deux MMS voisins.
 *
 * # Prerequis
 *
 * Ecrire dans `content://mms` exige le role SMS. Comme ailleurs, la precondition **tente**
 * l'ecriture au lieu d'interroger un reglage qui ment.
 */
@RunWith(AndroidJUnit4::class)
class MmsRowIdentityTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val resolver get() = context.contentResolver

    private val eraser by lazy { TelephonySystemCopyEraser(context) }

    private val ecrites = mutableListOf<Uri>()

    /**
     * Le nettoyage est VERIFIE, pas suppose. Un `runCatching` muet suffisait a laisser des MMS de
     * test sur un vrai telephone, et le controle externe ne pouvait pas le voir : mesure du
     * 2026-09-08, `adb shell content query --uri content://mms` ne rend rien meme quand des MMS
     * existent, faute des droits qu'a l'application. Ce qui ne se verifie que de l'interieur doit
     * se verifier ici.
     */
    @After
    fun tearDown() {
        val survivants = ecrites.filter { uri ->
            runCatching { resolver.delete(uri, null, null) }
            ligneSystemePresente(uri)
        }
        assertThat(survivants).isEmpty()
    }

    /**
     * Le trou lui-meme : meme sens, meme minute, **adresse differente**. Avant la relecture du
     * 2026-09-08, ce test supprimait le MMS de l'autre correspondant.
     */
    @Test
    fun unMmsDeMemeMinuteMaisDAdresseDifferente_nEstPasSupprime() {
        val dateSec = System.currentTimeMillis() / 1000L
        val uri = insertProbeMmsOrSkip(dateSec, EXPEDITEUR)

        val autreCorrespondant = message(uri, dateSec).copy(address = "+33699999999")

        assertThat(eraser.erase(autreCorrespondant)).isFalse()
        assertThat(ligneSystemePresente(uri)).isTrue()
    }

    /** Controle positif : un garde qui refuserait toujours satisferait aussi le test precedent. */
    @Test
    fun unMmsDuMemeCorrespondant_estBienSupprime() {
        val dateSec = System.currentTimeMillis() / 1000L
        val uri = insertProbeMmsOrSkip(dateSec, EXPEDITEUR)

        assertThat(eraser.erase(message(uri, dateSec))).isTrue()
        assertThat(ligneSystemePresente(uri)).isFalse()
    }

    /**
     * Le sens continue de discriminer, et la date aussi : ce test garde les deux criteres sous
     * surveillance pendant qu'on en ajoute un troisieme.
     */
    @Test
    fun unMmsDeSensOppose_nEstPasSupprime() {
        val dateSec = System.currentTimeMillis() / 1000L
        val uri = insertProbeMmsOrSkip(dateSec, EXPEDITEUR)

        val sortant = message(uri, dateSec).copy(
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.SENT,
        )

        assertThat(eraser.erase(sortant)).isFalse()
        assertThat(ligneSystemePresente(uri)).isTrue()
    }

    private fun insertProbeMmsOrSkip(dateSec: Long, expediteur: String): Uri {
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
        val inserted = tentative.getOrNull()
        assumeTrue(
            "ecriture dans content://mms refusee — " +
                (tentative.exceptionOrNull()?.toString() ?: "insert a rendu null, sans exception"),
            inserted != null,
        )
        ecrites += inserted!!
        val id = inserted.lastPathSegment

        // L'expediteur vit dans une table fille, et c'est precisement le point du test.
        val addr = ContentValues().apply {
            put("address", expediteur)
            put("type", PDU_HEADER_FROM)
            put("charset", CHARSET_UTF_8)
        }
        val addrEcrit = runCatching {
            resolver.insert(Uri.parse("content://mms/$id/addr"), addr)
        }.getOrNull()
        assumeTrue("ecriture dans content://mms/<id>/addr refusee", addrEcrit != null)

        return Uri.parse("content://mms/$id")
    }

    private fun ligneSystemePresente(uri: Uri): Boolean =
        resolver.query(uri, arrayOf(Telephony.Mms._ID), null, null, null)
            .use { it != null && it.moveToFirst() }

    private fun message(uri: Uri, dateSec: Long) = MessageEntity(
        conversationId = 1L,
        telephonyUri = uri.toString(),
        address = EXPEDITEUR,
        body = "",
        type = MessageType.MMS,
        direction = MessageDirection.INCOMING,
        date = dateSec * 1000L,
        dateSent = dateSec * 1000L,
        read = true,
        starred = false,
        status = MessageStatus.RECEIVED,
        errorCode = null,
        subId = null,
        scheduledAt = null,
        attachmentsCount = 1,
    )

    private companion object {
        const val EXPEDITEUR = "+33600000043"

        /** `MMS_MSG_BOX_INBOX` est `internal` au module `:data` — hors de portee ici. */
        const val MMS_BOITE_RECEPTION = 1
        const val MMS_TYPE_RETRIEVE_CONF = 132
        const val MMS_VERSION_1_0 = 0x10
        const val PDU_HEADER_FROM = 137
        const val CHARSET_UTF_8 = 106
    }
}
