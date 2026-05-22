package com.filestech.sms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * A conversation aggregates messages exchanged with one or more addresses.
 *
 * Mirrors the thread_id concept of Android's Telephony provider so we can map back and forth.
 */
@Serializable
@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["thread_id"], unique = true),
        Index(value = ["pinned", "last_message_at"]),
        Index(value = ["archived"]),
        Index(value = ["in_vault"]),
    ],
)
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "thread_id") val threadId: Long,
    @ColumnInfo(name = "addresses_csv") val addressesCsv: String,
    @ColumnInfo(name = "display_name") val displayName: String?,
    @ColumnInfo(name = "last_message_at") val lastMessageAt: Long,
    @ColumnInfo(name = "last_message_preview") val lastMessagePreview: String?,
    @ColumnInfo(name = "unread_count") val unreadCount: Int = 0,
    @ColumnInfo(name = "pinned") val pinned: Boolean = false,
    @ColumnInfo(name = "archived") val archived: Boolean = false,
    @ColumnInfo(name = "muted") val muted: Boolean = false,
    @ColumnInfo(name = "in_vault") val inVault: Boolean = false,
    @ColumnInfo(name = "draft") val draft: String? = null,
    @ColumnInfo(name = "notification_channel_id") val notificationChannelId: String? = null,
    /**
     * v1.11.0 — Apparence par contact. `null` = utilise le bleu marque par défaut
     * pour la bulle sortante. Sinon, ARGB Int stocké tel quel ([android.graphics.Color])
     * et appliqué uniquement à la bulle SORTANTE (les bulles entrantes restent
     * sur le slate-blue marque pour lisibilité). Les couleurs sont choisies parmi
     * une palette restreinte côté UI ([BubbleColorPalette]) pour garantir le
     * contraste WCAG AA avec le texte blanc.
     */
    @ColumnInfo(name = "bubble_color_argb") val bubbleColorArgb: Int? = null,
    /**
     * v1.11.0 — URI persistante d'un avatar custom (image choisie depuis la
     * galerie via `ActivityResultContracts.PickVisualMedia`). `null` = fallback
     * à l'avatar contact Android natif. L'URI est `content://` granted via
     * `FLAG_GRANT_READ_URI_PERMISSION` au moment du pick et persisté via
     * `ContentResolver.takePersistableUriPermission` côté caller.
     */
    @ColumnInfo(name = "avatar_uri") val avatarUri: String? = null,
)
