package com.filestech.sms.data.local.datastore

import com.filestech.sms.domain.safetycall.SafetyCallContact

/**
 * v1.9.0 — sérialise/désérialise la liste des [SafetyCallContact] pour
 * stockage dans DataStore (clé `K.safetyCallContactsJson`).
 *
 * **Format pipe-separated ligne par ligne** :
 * ```
 * Marie|+33612345678
 * |0698765432
 * Papa|0612000000
 * ```
 * `displayName` peut être vide (cas anonyme), `phoneNumber` requis.
 *
 * Choix d'un format trivial plutôt que JSON : pas de dépendance Android-only
 * (`org.json.JSONArray` n'est pas pure JVM), schéma à 2 champs, séparateur
 * `|` exclu des valeurs par sanitization, lisible/debuggable directement.
 *
 * Tolérant : input vide ou mal-formé retourne une liste vide plutôt que
 * de throw — on évite que l'user perde toute sa config DataStore par bug
 * de codec.
 *
 * **v1.10.0 — refacto C4** : renommé depuis `SafetyCallContactJsonCodec`
 * (nom historique trompeur — le format n'a jamais été du JSON). La clé
 * DataStore garde le suffixe `Json` pour rétro-compatibilité de stockage.
 */
internal object SafetyCallContactCodec {

    /**
     * Caractères qui DOIVENT être strippés des valeurs avant concaténation
     * dans le format pipe-separated : tout C0/C1 (`\n`, `\r`, `\t`, NUL…)
     * + le séparateur `|`. v1.9.0 audit fix SEC-4 : ajout de `\r` qui
     * n'était pas couvert.
     */
    private val FORBIDDEN_FIELD_CHARS = Regex("[\\u0000-\\u001F\\u007F|]")

    fun encode(contacts: List<SafetyCallContact>): String {
        return contacts.joinToString(separator = "\n") { c ->
            val name = (c.sanitizedDisplayName() ?: "")
                .replace(FORBIDDEN_FIELD_CHARS, "")
            val phone = c.phoneNumber
                .trim()
                .replace(FORBIDDEN_FIELD_CHARS, "")
            "$name|$phone"
        }
    }

    fun decode(raw: String?): List<SafetyCallContact> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf('|')
                if (idx < 0) return@mapNotNull null
                val name = line.substring(0, idx).trim().ifEmpty { null }
                val phone = line.substring(idx + 1).trim()
                if (phone.isEmpty()) return@mapNotNull null
                val candidate = SafetyCallContact(displayName = name, phoneNumber = phone)
                // v1.9.0 audit fix SEC-4 — défense en profondeur : un
                // DataStore restauré depuis backup tiers pourrait contenir
                // un numéro invalide qui n'est pas passé par la validation
                // ViewModel. Le filet [SafetyCallContact.isValid] au decode
                // garantit que rien d'invalide n'arrive jusqu'au sender.
                if (!candidate.isValid()) return@mapNotNull null
                candidate
            }
            .toList()
    }
}
