package com.filestech.sms.data.sms

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
 * 2026-11-11 (issue #89) — **un SMS reçu garde ses deux heures chez Android, et l'import les relit telles
 * quelles**, sur le VRAI fournisseur `content://sms`.
 *
 * `TelephonyReader.insertInboxSms` écrivait l'heure du réseau dans `DATE` ET `DATE_SENT`. Désormais `DATE`
 * reçoit l'heure d'arrivée, celle qui ordonne le fil, et `DATE_SENT` l'heure du réseau
 * (cf. `com.filestech.sms.domain.sms.HorodatageSmsRecu`). L'import (`readSmsSince`) doit rendre les deux à
 * l'identique : c'est ce qui garde la ligne écrite par le receveur et celle d'une resynchronisation égales.
 *
 * Prérequis : le rôle d'application SMS (`cmd role add-role-holder android.app.role.SMS
 * com.filestech.sms.debug`). Sans lui, les tests sont IGNORÉS avec leur motif, jamais comptés verts.
 */
@RunWith(AndroidJUnit4::class)
class HeureDuSmsRecuTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val resolver get() = context.contentResolver

    private val reader by lazy { TelephonyReader(context) }

    private val ecrites = mutableListOf<Uri>()

    /** Le nettoyage se vérifie, il ne se suppose pas (cf. `SystemRowIdentityTest.tearDown`). */
    @After
    fun tearDown() {
        val survivants = ecrites.filter { uri ->
            runCatching { resolver.delete(uri, null, null) }
            runCatching {
                resolver.query(uri, arrayOf(Telephony.Sms._ID), null, null, null).use { it != null && it.moveToFirst() }
            }.getOrDefault(false)
        }
        assertThat(survivants).isEmpty()
    }

    @Test
    fun lHeureDArriveeEtLHeureDuReseauSontGardeesEtRelues() = runBlocking {
        val uri = ecrireOuIgnorer(dateSent = RESEAU)

        val relue = relire(uri)

        assertThat(relue.date).isEqualTo(ARRIVEE)
        assertThat(relue.dateSent).isEqualTo(RESEAU)
    }

    @Test
    fun sansHeureDuReseauLHeureDEnvoiResteAbsente() = runBlocking {
        val uri = ecrireOuIgnorer(dateSent = null)

        val relue = relire(uri)

        assertThat(relue.date).isEqualTo(ARRIVEE)
        assertThat(relue.dateSent).isNull()
    }

    private fun ecrireOuIgnorer(dateSent: Long?): Uri {
        val tentative = runCatching { reader.insertInboxSms(ADRESSE, "heure #89", ARRIVEE, dateSent) }
        val uri = tentative.getOrNull()
        assumeTrue(
            "ecriture dans content://sms refusee — " +
                (tentative.exceptionOrNull()?.toString() ?: "pas le role SMS (insert refuse ou nul)"),
            uri != null,
        )
        ecrites += uri!!
        return uri
    }

    /** La ligne telle que l'import la rend ; ignore le test si la lecture est refusée (READ_SMS). */
    private suspend fun relire(uri: Uri): com.filestech.sms.data.local.db.entity.MessageEntity {
        val id = uri.lastPathSegment!!.toLong()
        val lues = runCatching {
            val rangs = mutableListOf<TelephonyReader.DeltaRow>()
            reader.readSmsSince(sinceId = id - 1) { page -> rangs += page }
            rangs
        }
        assumeTrue("lecture de content://sms refusee — READ_SMS n'est pas accorde", lues.isSuccess)
        val ligne = lues.getOrThrow().single { it.telephonyId == id }
        return ligne.entity
    }

    private companion object {
        const val ADRESSE = "+33699999989"

        /** 2026-11-11T20:41:13Z, l'heure de l'issue #89. */
        const val ARRIVEE = 1_794_429_673_000L

        /** Une heure plus tôt : un opérateur dont le décalage horaire n'a pas suivi le changement d'heure. */
        const val RESEAU = ARRIVEE - 3_600_000L
    }
}
