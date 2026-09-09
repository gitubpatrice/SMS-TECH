package com.filestech.sms.domain.model

/**
 * v1.28.3 (audit global, X-08 — mesuré sur le S9) — **le titre d'un groupe.**
 *
 * La liste des noms de ses membres, dans l'ordre des adresses ; un membre inconnu garde son
 * numéro brut. `null` si aucun membre n'est un contact : l'écran retombe alors sur les numéros,
 * exactement comme avant — la règle n'invente rien, elle cesse seulement de taire les noms.
 *
 * Une seule fonction pour les deux créateurs de groupe — la composition
 * (`ConversationRepositoryImpl.findOrCreate`) et la réception (`ConversationMirror`) — parce
 * que deux règles finissent par diverger.
 */
object GroupTitle {

    /** @param membres paires (numéro brut, nom de contact ou `null`). */
    fun of(membres: List<Pair<String, String?>>): String? {
        if (membres.none { (_, nom) -> !nom.isNullOrBlank() }) return null
        return membres.joinToString(", ") { (numero, nom) -> nom?.takeIf { it.isNotBlank() } ?: numero }
    }
}
