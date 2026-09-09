package com.filestech.sms.ui.screens.thread

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * v1.28.3 (audit global, X-02 — mesuré sur le S9) — **une fiche de contact n'est pas un fichier.**
 *
 * Le sélecteur rend l'URI d'une fiche ; la carte de visite se lit à `CONTENT_VCARD_URI/<clé>`.
 * Ce fichier prouve la traduction, et le repli : une fiche illisible rend `null`, et l'appelant
 * garde son message d'échec — qui devient vrai.
 *
 * JUnit 4 sous Robolectric, comme `HiltRobolectricSmokeTest` : c'est le moteur *vintage* qui
 * l'exécute. Un faux fournisseur de contacts est enregistré sous l'autorité réelle, pour que la
 * classe testée traverse un vrai `ContentResolver`.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [33])
class ContactCardSourceTest {

    /** Fournisseur de contacts factice : une seule fiche, `lookup/cle-jean`, nommée Jean Dupont. */
    class FauxContacts : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val colonnes = arrayOf(ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME)
            val curseur = MatrixCursor(colonnes)
            if (uri.toString().contains("lookup/cle-jean")) curseur.addRow(arrayOf("cle-jean", "Jean Dupont"))
            return curseur
        }

        override fun getType(uri: Uri): String = ContactsContract.Contacts.CONTENT_ITEM_TYPE
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }

    private fun source(): ContactCardSource {
        Robolectric.buildContentProvider(FauxContacts::class.java).create(ContactsContract.AUTHORITY)
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return ContactCardSource(ctx.contentResolver)
    }

    @Test
    fun `une fiche devient l'adresse de sa carte de visite, nommee et typee`() {
        val fiche = Uri.parse("content://${ContactsContract.AUTHORITY}/contacts/lookup/cle-jean/42")

        val carte = source().resoudre(fiche)

        assertThat(carte).isNotNull()
        val attendue = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, "cle-jean")
        assertThat(carte!!.uri).isEqualTo(attendue)
        assertThat(carte.nomDeFichier).isEqualTo("Jean Dupont.vcf")
        assertThat(carte.mime).isEqualTo("text/x-vcard")
    }

    /** Le repli : une fiche que le fournisseur ne connaît pas rend `null`, jamais une URI inventée. */
    @Test
    fun `une fiche inconnue rend null`() {
        val fiche = Uri.parse("content://${ContactsContract.AUTHORITY}/contacts/lookup/inconnue/7")

        assertThat(source().resoudre(fiche)).isNull()
    }

    @Test
    fun `le nom de fichier est assaini et jamais vide`() {
        assertThat(ContactCardSource.nomDeFichier("A/B:C*?")).isEqualTo("A_B_C__.vcf")
        assertThat(ContactCardSource.nomDeFichier("   ")).isEqualTo("contact.vcf")
        assertThat(ContactCardSource.nomDeFichier(null)).isEqualTo("contact.vcf")
    }
}
