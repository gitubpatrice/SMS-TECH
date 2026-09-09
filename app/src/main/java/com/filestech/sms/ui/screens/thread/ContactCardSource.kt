package com.filestech.sms.ui.screens.thread

import android.content.ContentResolver
import android.net.Uri
import android.provider.ContactsContract

/**
 * v1.28.3 (audit global du 2026-09-09, X-02 — **mesuré sur le S9, invisible à la lecture**) —
 * **ce que le sélecteur de contacts rend n'est pas un fichier.**
 *
 * `ActivityResultContracts.PickContact` rend `content://com.android.contacts/contacts/lookup/…`,
 * l'URI d'une FICHE. `ContentResolver.openInputStream` n'a rien à en lire, et `getType` y répond
 * `vnd.android.cursor.item/contact`. La copie en cache échouait donc, à tous les coups, avec
 * « Impossible de lire la pièce jointe » — joindre un contact depuis le composeur n'a jamais
 * fonctionné. Le chemin ENTRANT, lui, savait déjà afficher une carte de visite (F16) : encore un
 * jumeau asymétrique, dans l'autre sens.
 *
 * La carte de visite d'un contact se lit à une autre adresse, [ContactsContract.Contacts.CONTENT_VCARD_URI]
 * suivie de sa clé de recherche : le fournisseur y sert un flux `text/x-vcard` que la copie en
 * cache sait recopier. Cette classe fait cette traduction, et rien d'autre.
 */
class ContactCardSource(private val resolver: ContentResolver) {

    /** La carte de visite prête à être copiée : son URI, le nom de fichier à montrer, son MIME. */
    data class Carte(val uri: Uri, val nomDeFichier: String, val mime: String = MIME_VCARD)

    /**
     * `null` quand la fiche n'est pas lisible (permission retirée entre-temps, fiche supprimée,
     * fournisseur absent) : l'appelant garde alors son message d'échec, qui devient vrai.
     */
    fun resoudre(fiche: Uri): Carte? = runCatching {
        resolver.query(
            fiche,
            arrayOf(ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { curseur ->
            if (!curseur.moveToFirst()) return@use null
            val cle = curseur.getString(0)?.takeIf { it.isNotBlank() } ?: return@use null
            val nom = curseur.getString(1)
            Carte(
                uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, cle),
                nomDeFichier = nomDeFichier(nom),
            )
        }
    }.getOrNull()

    companion object {
        const val MIME_VCARD = "text/x-vcard"

        /** `Jean Dupont` → `Jean Dupont.vcf` ; un nom vide ou illisible → `contact.vcf`. */
        fun nomDeFichier(nom: String?): String {
            val propre = nom.orEmpty().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim()
            return (propre.ifBlank { "contact" }) + ".vcf"
        }
    }
}
