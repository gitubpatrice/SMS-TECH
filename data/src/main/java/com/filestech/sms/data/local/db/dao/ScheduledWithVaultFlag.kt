package com.filestech.sms.data.local.db.dao

import androidx.room.ColumnInfo
import androidx.room.Embedded
import com.filestech.sms.data.local.db.entity.ScheduledMessageEntity

/**
 * v1.28.3 (F02) — un envoi programmé, **accompagné du statut coffre de sa conversation**.
 *
 * # Le défaut
 *
 * `observePending` et `observeFailed` étaient deux `SELECT * FROM scheduled_messages` sans le
 * moindre filtre, à comparer aux requêtes de `ConversationDao` qui portent toutes
 * `WHERE in_vault = 0`. L'écran « Messages programmés » affichait donc en clair le corps et les
 * destinataires d'un message programmé depuis une conversation du coffre — sans que le second
 * facteur ait été franchi, et y compris en session leurre.
 *
 * La carte qui mène à cet écran n'était même pas sous le garde `!isPanicDecoy` des Réglages,
 * contrairement à ses quatre voisines (Sauvegarde, Appel de sécurité, Verrouillage, Coffre).
 * C'est le motif exact que la v1.27.11 avait fermé sur « Réinitialiser tous les réglages » :
 * une entrée oubliée d'un garde que tout ce qui l'entoure applique.
 *
 * # Pourquoi une projection plutôt qu'un filtre SQL
 *
 * Le filtre ne peut pas être écrit en SQL seul : la visibilité dépend de l'état de la SESSION —
 * le coffre a-t-il été ouvert dans cette session, est-on en session leurre — que la base ignore.
 * La requête rend donc le drapeau, et `ScheduledMessageRepositoryImpl` applique la même règle
 * que `ConversationRepositoryImpl.observeOne` : masqué si `inVault && (leurre || coffre fermé)`.
 *
 * `COALESCE(c.in_vault, 0)` couvre le `LEFT JOIN` sans correspondance —
 * `scheduled_messages.conversation_id` est nullable et n'est pas une clé étrangère, un envoi
 * programmé pouvant survivre à la disparition de son fil. Sans `COALESCE`, ces lignes rendraient
 * `NULL` et le drapeau serait indéterminé ; on les traite comme hors coffre, ce qui est le cas
 * réel — un envoi sans conversation n'a pas de contenu protégé à masquer.
 */
data class ScheduledWithVaultFlag(
    @Embedded val message: ScheduledMessageEntity,
    @ColumnInfo(name = "in_vault") val inVault: Boolean,
)
