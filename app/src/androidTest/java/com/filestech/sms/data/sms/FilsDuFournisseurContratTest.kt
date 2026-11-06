package com.filestech.sms.data.sms

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 2026-11-06 (courriel d'un utilisateur Visible/Verizon) — **un fil de groupe se relit avec ses membres sur un VRAI
 * fournisseur**. `FilsDuFournisseurTest` ne décode que des curseurs simulés : rien d'autre ne prouve que
 * `content://mms-sms/canonical-addresses` et `content://mms-sms/conversations?simple=true` se lisent, et rendent ce
 * que l'import attend, sur l'appareil. Le S9 (Android 10, fournisseur Samsung) n'avait aucun groupe à relire.
 *
 * Le test crée un fil à deux membres (`Threads.getOrCreateThreadId`), y range un SMS — un fil vide est supprimé par
 * le fournisseur —, le relit, puis supprime le SMS, ce qui supprime le fil. Numéros de la plage fictive 555-01xx.
 *
 * Écrire dans `content://sms` exige le rôle d'application SMS par défaut : sans lui, le test est IGNORÉ, pas vert
 * (même sonde fonctionnelle que `SystemProviderContractTest`).
 */
@RunWith(AndroidJUnit4::class)
class FilsDuFournisseurContratTest {

    private companion object {
        const val A = "+15550100101"
        const val B = "+15550100102"
    }

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val resolver get() = context.contentResolver

    @Test
    fun unFilDeGroupeSeRelitAvecSesMembresEtSesMessages() {
        val cree = runCatching { Telephony.Threads.getOrCreateThreadId(context, setOf(A, B)) }.getOrNull()
        assumeTrue("fil non cree — READ_SMS ou role SMS absent", cree != null && cree > 0L)
        val fil = cree!!
        // Sans le rôle, l'écriture n'échoue pas : `ContentProvider.rejectInsert` rend une fausse URI finissant par
        // `/0` (2e relecture du 2026-11-06). Elle compte comme refusée, sinon le test échouerait au lieu d'être ignoré.
        val insere = runCatching { insererDans(fil) }.getOrNull()?.takeIf { it.lastPathSegment != "0" }
        assumeTrue("ecriture dans content://sms refusee — cette application n'a pas le role SMS", insere != null)
        // Forme canonique : Android 10 rend `content://sms/sent/<id>` (cf. `SystemProviderContractTest`).
        val sms = Uri.parse("content://sms/${insere!!.lastPathSegment}")
        try {
            val lecteur = TelephonyReader(context)

            val fils = checkNotNull(lecteur.lireFilsDuFournisseur()) { "fournisseur illisible" }
            assertThat(fils[fil]).containsExactly(A, B)

            val messages = checkNotNull(lecteur.lireMessagesDesFils(listOf(fil))) { "messages illisibles" }
            assertThat(messages[fil]).containsExactly(sms.toString())
        } finally {
            runCatching { resolver.delete(sms, null, null) }
        }
    }

    private fun insererDans(fil: Long): Uri? {
        val maintenant = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put(Telephony.Sms.THREAD_ID, fil)
            put(Telephony.Sms.ADDRESS, A)
            put(Telephony.Sms.BODY, "SMS Tech — sonde de test du 2026-11-06, a supprimer")
            put(Telephony.Sms.DATE, maintenant)
            put(Telephony.Sms.DATE_SENT, maintenant)
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
        }
        return resolver.insert(Telephony.Sms.Sent.CONTENT_URI, cv)
    }
}
