package com.filestech.sms.data.repository

/**
 * v1.6.1 (audit QUAL-18) — fonction utilitaire pure extraite de
 * [ConversationRepositoryImpl] pour permettre des tests unitaires directs sans
 * instancier le repo (qui requiert N dépendances Hilt).
 *
 * Audit P11 + R3 : SQLite FTS4 traite `"`, `*`, `^`, `:`, `-`, `(`, `)`, `+` comme de
 * la syntaxe. Une query contenant un de ces caractères faisait lever `SQLiteException`
 * au runtime. La logique :
 *  - drop des caractères de contrôle (C0, DEL, BiDi, zero-width)
 *  - per token : strip de chaque caractère réservé FTS pour ne garder que du word-char
 *    safe
 *  - per (non-blank) token : append `*` pour activer le prefix matching
 *  - join des tokens avec un espace simple (AND implicite côté FTS)
 *
 * `internal` car ce n'est pas une API publique du module — uniquement consommé par le
 * repository et ses tests.
 */
internal fun escapeFtsQuery(input: String): String {
    val cleaned = input
        .replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
        .replace(Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]"), "")
        .trim()
    if (cleaned.isEmpty()) return ""
    return cleaned
        .split(Regex("\\s+"))
        .asSequence()
        .map { it.replace(Regex("[\"*^():+\\-]"), "") }
        .filter { it.isNotBlank() }
        .joinToString(separator = " ") { "${it}*" }
}
