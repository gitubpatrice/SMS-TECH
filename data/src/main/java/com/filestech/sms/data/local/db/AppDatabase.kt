package com.filestech.sms.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.BlockedNumberDao
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.dao.QuickReplyDao
import com.filestech.sms.data.local.db.dao.ScheduledMessageDao
import com.filestech.sms.data.local.db.entity.AttachmentEntity
import com.filestech.sms.data.local.db.entity.BlockedNumberEntity
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.data.local.db.entity.MessageFts
import com.filestech.sms.data.local.db.entity.QuickReplyEntity
import com.filestech.sms.data.local.db.entity.ScheduledMessageEntity

@Database(
    version = AppDatabase.SCHEMA_VERSION,
    exportSchema = true,
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MessageFts::class,
        AttachmentEntity::class,
        BlockedNumberEntity::class,
        ScheduledMessageEntity::class,
        QuickReplyEntity::class,
    ],
)
// v1.16.0 — TypeConverters pour les 3 enums MessageStatus/Type/Direction ↔ Int.
// Colonnes SQL restent INTEGER NOT NULL ; identityHash Room inchangé.
@TypeConverters(MessageEnumConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun blockedNumberDao(): BlockedNumberDao
    abstract fun scheduledMessageDao(): ScheduledMessageDao
    abstract fun quickReplyDao(): QuickReplyDao

    companion object {
        const val DATABASE_NAME = "smstech.db"
        // v2 (2026-05-15): adds `messages.reply_to_message_id` + matching index for the
        //   contextual-reply feature (#8). Migration in `Migrations.kt`.
        // v3 (2026-05-16, v1.2.6 audit F2): adds `messages.mms_system_id` (nullable Long) +
        //   matching index. Lets the retry path delete the stale `content://mms` row from the
        //   previous attempt before inserting a fresh outbox row — guarantees one MMS = one
        //   system-provider row visible across all SMS apps, even during retry windows.
        // v4 (2026-05-16, v1.3.0): adds `messages.reaction_emoji` (nullable TEXT) for the
        //   per-message local emoji reaction feature. Pas d'index (jamais filtré dessus).
        // v5 (2026-05-16, v1.3.0 audit P1): adds `index_messages_date` so the auto-purge
        //   `WHERE date < cutoff` (TelephonySyncWorker tick) ne fait plus de full scan
        //   SQLCipher. Bump séparé de v4 pour absorber proprement les users qui ont
        //   reçu un build v1.3.0 intermédiaire avec migration v3→v4 sans index.
        // v6 (2026-05-17, v1.3.7 G4 audit): DROP TABLE conversation_overrides — table
        //   morte (entity + DAO existaient mais aucun consommateur métier). Confirmé
        //   via grep transversal : seulement référencé par AppDatabase + DatabaseModule.
        //   Migration v5→v6 `DROP TABLE IF EXISTS conversation_overrides` (idempotente).
        // v7 (2026-05-22, v1.11.0): adds `conversations.bubble_color_argb INTEGER` +
        //   `conversations.avatar_uri TEXT` (both nullable) for the per-contact appearance
        //   feature. Strictly additive — legacy rows project NULL = default bubble color
        //   + default contact avatar. Migration v6→v7 in `Migrations.kt`.
        // v8 (2026-09-07, v1.27.11): AUCUN changement de schema — migration de DONNEES.
        //   `messages.telephony_uri` etait enregistre sous deux formes pour la meme ligne du
        //   fournisseur systeme (`content://sms/sent/<id>` cote application selon la version
        //   d'Android, `content://sms/<id>` cote import), que l'index UNIQUE ne rapprochait
        //   pas : une resynchronisation complete dupliquait chaque message ecrit par l'app.
        //   La migration normalise et supprime les doublons deja crees. Cf. `Migrations.kt`.
        // v9 (2026-09-09, v1.28.3): `conversations.thread_id` passe de `INTEGER NOT NULL` a
        //   `INTEGER` nullable. C'est le premier changement de schema NON additif du projet, et
        //   il exige une recreation de table — SQLite ne sait pas retirer un `NOT NULL`.
        //   Motif : la colonne porte un index UNIQUE, et toute conversation sans fil systeme y
        //   inscrivait la sentinelle partagee `0L`. La deuxieme conversation locale entrait donc
        //   en conflit, et `OnConflictStrategy.REPLACE` supprimait la premiere — ses messages et
        //   ses pieces jointes suivant par `ForeignKey.CASCADE`. Reproduit sur emulateur par la
        //   relecture externe (F01). `NULL` retablit la seule semantique juste : SQLite tient
        //   deux `NULL` pour distincts sous un index UNIQUE. La migration convertit en `NULL`
        //   tout `thread_id <= 0`, ce qui absorbe aussi les placeholders negatifs que
        //   `BackupService` fabriquait depuis la v1.15.2 pour contourner le meme piege.
        // v10 (2026-09-09, v1.28.3): ajoute `scheduled_messages.claimed_at INTEGER` (nullable).
        //   Strictement additive. C'est le bail du verrou d'envoi : l'etat `SENDING` disait
        //   « une execution a revendique cet envoi » sans dire QUAND, si bien qu'une execution
        //   morte en vol laissait la ligne `SENDING` a vie — invisible des « Echecs », et hors
        //   d'atteinte de son propre bouton « Annuler », qui ne matche que `PENDING`. Avec la
        //   date, un bail expire se conclut en `INTERRUPTED` : issue inconnue, mais ATTEIGNABLE.
        //   Les lignes existantes projettent NULL = bail expire, ce qui debloque le parc. F20.
        // v11 (2026-09-09, v1.28.3): ajoute `messages.send_attempt INTEGER NOT NULL DEFAULT 0`.
        //   Strictement additive. Les `PendingIntent` de suivi ne portaient que l'id Room : une
        //   relance reutilisant le meme id, l'accuse TARDIF de la tentative precedente etait
        //   indiscernable de celui de la tentative courante. Or la relance venait de retrograder
        //   la ligne en `PENDING`, donc la regle monotone ne le filtrait plus : un `FAILED` en
        //   retard ecrivait 3, sommet de l'echelle, que le succes reel de la nouvelle tentative
        //   ne pouvait PLUS JAMAIS promouvoir. Bulle rouge definitive sur un message recu. F23.
        // v12 (2026-09-09, v1.28.3): ajoute `conversations.custom_name TEXT` — nom choisi d'un
        //   groupe, local au telephone. Strictement additive. Colonne separee de `display_name`,
        //   que la resolution des contacts reecrit.
        const val SCHEMA_VERSION = 12
    }
}
