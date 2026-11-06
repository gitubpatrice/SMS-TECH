package com.filestech.sms.ui.screens.conversations

import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.MessageSearchHit

/**
 * 2026-11-06 — les trois vues de la liste des conversations.
 *
 * Une conversation BLOQUÉE — tous ses numéros sur la liste noire, cf. `Conversation.blocked` — n'apparaît
 * que dans [BLOQUEES], archivée ou non. La liste principale et les Archives n'en montrent aucune.
 * Depuis la v1.25.3, elles descendaient en fin de liste principale sous un en-tête « Bloquées », ce
 * qui mêlait deux usages et alourdissait la liste (demande de Patrice, 2026-11-06). Le dossier s'ouvre
 * par une icône à côté du coffre, affichée seulement s'il existe au moins une conversation bloquée.
 *
 * Les nouveaux messages d'un numéro bloqué sont supprimés dès leur arrivée (`IncomingBlockPolicy`) :
 * le dossier ne contient que les échanges d'avant le blocage.
 */
enum class VueConversations { PRINCIPALE, ARCHIVES, BLOQUEES }

/**
 * Ce qu'une vue montre.
 *
 * @property bloqueesExistent au moins une conversation bloquée, archivée ou non : l'icône du dossier.
 * @property idsBloques pour écarter de la recherche les messages des conversations bloquées.
 */
internal data class Repartition(
    val conversations: List<Conversation>,
    val bloqueesExistent: Boolean,
    val idsBloques: Set<Long>,
)

/**
 * Répartit les conversations hors coffre entre les trois vues. [horsCoffre] est UNE lecture
 * (`ConversationRepository.observeAllNonVault`), archivées comprises : chaque vue part du même instantané,
 * et l'ordre de la lecture est conservé dans chacune.
 */
internal fun repartir(vue: VueConversations, horsCoffre: List<Conversation>): Repartition {
    val bloquees = horsCoffre.filter { it.blocked }
    val visibles = when (vue) {
        VueConversations.PRINCIPALE -> horsCoffre.filter { !it.archived && !it.blocked }
        VueConversations.ARCHIVES -> horsCoffre.filter { it.archived && !it.blocked }
        VueConversations.BLOQUEES -> bloquees
    }
    return Repartition(
        conversations = visibles,
        bloqueesExistent = bloquees.isNotEmpty(),
        idsBloques = bloquees.mapTo(HashSet(bloquees.size)) { it.id },
    )
}

/**
 * Les messages trouvés par la recherche, sans ceux des conversations bloquées : celles-ci ne sont plus dans
 * la liste, leur texte ne doit pas y revenir par la recherche. (Le dossier des bloquées, lui, ne cherche
 * pas dans les messages : cf. `ConversationsViewModel.messageHits`.)
 */
internal fun sansConversationsBloquees(hits: List<MessageSearchHit>, idsBloques: Set<Long>): List<MessageSearchHit> =
    if (idsBloques.isEmpty()) hits else hits.filterNot { it.conversation.id in idsBloques }
