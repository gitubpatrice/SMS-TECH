package com.filestech.sms.data.sms

import android.database.Cursor
import com.filestech.sms.core.ext.stripInvisibleChars
import com.filestech.sms.core.ext.stripMmsAddressSuffix

/**
 * 2026-11-06 (courriel d'un utilisateur Visible/Verizon) — **les fils du fournisseur Android et leurs
 * destinataires**, pour que l'import range un fil de groupe dans une conversation de groupe.
 *
 * Jusqu'ici l'import groupait bien les messages par `thread_id`, mais ouvrait la conversation sur la SEULE adresse
 * de la première ligne : chaque groupe devenait la conversation d'un de ses membres, mêlée à ses échanges privés
 * avec lui, et une réponse n'allait qu'à lui.
 *
 * Le fournisseur tient les destinataires d'un fil dans `threads.recipient_ids` (identifiants séparés par des
 * espaces) et leurs adresses dans `canonical_addresses`. Les deux lectures sont celles des applications SMS libres
 * qui reconstituent les groupes : `content://mms-sms/conversations?simple=true` et
 * `content://mms-sms/canonical-addresses`. Les fonctions ci-dessous ne font que DÉCODER les curseurs, sans
 * Android : la requête vit dans [TelephonyReader.lireFilsDuFournisseur].
 */
internal object FilsDuFournisseur {

    /** `canonical_addresses` : `_id` → adresse nettoyée comme celles d'un PDU ; les adresses vides sont omises. */
    fun adresses(curseur: Cursor): Map<Long, String> {
        val adresses = HashMap<Long, String>()
        while (curseur.moveToNext()) {
            val adresse = curseur.getString(1)?.stripMmsAddressSuffix()?.stripInvisibleChars()?.trim()
            if (!adresse.isNullOrEmpty()) adresses[curseur.getLong(0)] = adresse
        }
        return adresses
    }

    /**
     * `threads` (`_id`, `recipient_ids`) : `thread_id` → adresses de ses destinataires, dans l'ordre du fournisseur.
     * Un identifiant sans adresse connue est ignoré ; un fil sans aucune adresse est omis.
     */
    fun fils(curseur: Cursor, adresses: Map<Long, String>): Map<Long, List<String>> {
        val fils = HashMap<Long, List<String>>()
        while (curseur.moveToNext()) {
            val destinataires = curseur.getString(1).orEmpty()
                .split(' ')
                .mapNotNull { it.toLongOrNull()?.let(adresses::get) }
            if (destinataires.isNotEmpty()) fils[curseur.getLong(0)] = destinataires
        }
        return fils
    }

    /** `content://sms` ou `content://mms` (`_id`, `thread_id`) : `thread_id` → URI des messages, forme de l'import. */
    fun messages(curseur: Cursor, prefixe: String, dans: MutableMap<Long, MutableList<String>>) {
        while (curseur.moveToNext()) {
            dans.getOrPut(curseur.getLong(1)) { ArrayList() } += prefixe + curseur.getLong(0)
        }
    }
}
