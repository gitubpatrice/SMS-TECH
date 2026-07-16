package com.filestech.sms.data.repository

/**
 * v1.22.x — planification **pure** (testable en JVM, sans Android) de la fusion des conversations
 * 1-to-1 du même numéro laissées en double par les versions antérieures aux correctifs de
 * threading. Séparée de [ConversationMirror.dedupeSameNumberConversations] pour que la décision
 * « quelles conversations fusionner et laquelle survit » soit couverte par des tests unitaires.
 */
internal data class DedupCandidate(
    val id: Long,
    val rawAddress: String,
    val lastMessageAt: Long,
)

internal data class DedupMergePlan(
    val survivorId: Long,
    val victimIds: List<Long>,
)

/**
 * Regroupe les [candidates] par **clé canonique** ([canonicalKey]) et produit un [DedupMergePlan]
 * pour chaque groupe d'au moins 2 conversations.
 *
 * Contrats de sûreté (c'est ici que se joue « ne fusionne jamais deux personnes différentes ») :
 *   - [canonicalKey] renvoie `null` quand le numéro n'est pas normalisable de façon fiable
 *     (short code, expéditeur alphanumérique, région inconnue) → la conversation est **ignorée**,
 *     jamais fusionnée à l'aveugle. L'appelant fournit une clé E.164 (`+33…`), strictement plus
 *     discriminante que les 8 derniers chiffres — deux numéros distincts partageant leur suffixe
 *     (ex. `06…12345678` vs `07…12345678`) obtiennent des clés E.164 différentes → non fusionnés.
 *   - le **survivant** est la conversation la plus récemment active (`lastMessageAt` max) ; en cas
 *     d'égalité, le plus petit `id` (la plus ancienne, généralement la conversation « historique »
 *     que l'utilisateur reconnaît). Déterministe → dédup idempotente et rejouable.
 */
internal fun planSameNumberMerges(
    candidates: List<DedupCandidate>,
    canonicalKey: (rawAddress: String) -> String?,
): List<DedupMergePlan> =
    candidates
        .mapNotNull { c -> canonicalKey(c.rawAddress)?.let { key -> key to c } }
        .groupBy({ it.first }, { it.second })
        .values
        .filter { group -> group.size >= 2 }
        .map { group ->
            val survivor = group.sortedWith(
                compareByDescending<DedupCandidate> { it.lastMessageAt }.thenBy { it.id },
            ).first()
            DedupMergePlan(
                survivorId = survivor.id,
                victimIds = group.filter { it.id != survivor.id }.map { it.id },
            )
        }
