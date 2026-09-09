package com.filestech.sms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.filestech.sms.domain.model.ScheduledState

@Entity(
    tableName = "scheduled_messages",
    indices = [Index(value = ["scheduled_at"])],
)
data class ScheduledMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: Long?,
    @ColumnInfo(name = "addresses_csv") val addressesCsv: String,
    @ColumnInfo(name = "body") val body: String,
    @ColumnInfo(name = "scheduled_at") val scheduledAt: Long,
    @ColumnInfo(name = "sub_id") val subId: Int? = null,
    @ColumnInfo(name = "attachments_json") val attachmentsJson: String? = null,
    // v1.17.0 audit BACK-M1 — `state` typé enum (était Int). Stockage SQL inchangé via
    // [com.filestech.sms.data.local.db.MessageEnumConverters] qui mappe rawValue ↔ Int.
    // Cohérent avec MessageStatus/Type/Direction convertis en v1.16.0.
    @ColumnInfo(name = "state") val state: ScheduledState = ScheduledState.PENDING,
    @ColumnInfo(name = "work_id") val workId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /**
     * v1.28.3 (F20) — instant où l'envoi a été revendiqué (`PENDING → SENDING`), en millisecondes
     * epoch, `null` tant qu'il ne l'a pas été.
     *
     * C'est le **bail** du verrou. Sans lui, l'état `SENDING` ne portait aucune date et rien ne
     * permettait de distinguer « une exécution est en train d'envoyer » de « l'exécution qui
     * avait revendiqué cet envoi est morte il y a trois jours ». Les deux se lisaient `4`, et le
     * second cas restait donc bloqué à vie.
     *
     * `null` sur une ligne déjà `SENDING` au moment de la migration 9 → 10 vaut **bail expiré** :
     * la revendication est forcément antérieure au redémarrage qui a ouvert la base, donc son
     * exécution n'existe plus. C'est ce qui débloque le parc déjà coincé.
     */
    @ColumnInfo(name = "claimed_at") val claimedAt: Long? = null,
)
