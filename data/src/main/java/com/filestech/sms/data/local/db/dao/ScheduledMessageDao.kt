package com.filestech.sms.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.filestech.sms.data.local.db.entity.ScheduledMessageEntity
import com.filestech.sms.domain.model.ScheduledState
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledMessageDao {

    /**
     * v1.26.1 (audit H6) — inclut l'état `SENDING` (4) en plus de `PENDING` (0).
     *
     * Une ligne revendiquée puis interrompue en vol (processus tué pendant l'envoi) doit rester
     * VISIBLE dans « Programmés » : la sortir de cette liste reproduirait exactement le défaut
     * des lignes `CANCELLED`, qu'aucune liste n'affiche et qui étaient donc inatteignables.
     */
    @Query("SELECT * FROM scheduled_messages WHERE state IN (0, 4) ORDER BY scheduled_at ASC")
    fun observePending(): Flow<List<ScheduledMessageEntity>>

    /**
     * v1.25.3 (audit H6) — les envois abandonnés après épuisement des tentatives
     * ([com.filestech.sms.system.scheduler.ScheduledSendAttempt]). Sans cette requête ils
     * sortaient de [observePending] et disparaissaient de l'écran : l'utilisateur ne voyait ni
     * l'échec, ni le message. Les plus récents d'abord — c'est l'échec du jour qu'on vient voir.
     *
     * `state = 2` littéral pour la même raison que le `state = 0` ci-dessus : Room lie les
     * paramètres, pas les constantes, et l'enum est convertie par
     * [com.filestech.sms.data.local.db.MessageEnumConverters].
     */
    @Query("SELECT * FROM scheduled_messages WHERE state = 2 ORDER BY scheduled_at DESC")
    fun observeFailed(): Flow<List<ScheduledMessageEntity>>

    @Query("SELECT * FROM scheduled_messages WHERE id = :id")
    suspend fun findById(id: Long): ScheduledMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ScheduledMessageEntity): Long

    /**
     * v1.22.x — reparent des envois programmés lors de la fusion de doublons de conversation
     * (dédup même numéro). Pas de FK sur `conversation_id` : reparent explicite par cohérence, pour
     * qu'un envoi programmé pointe vers la conversation survivante et non vers une ligne supprimée.
     */
    @Query("UPDATE scheduled_messages SET conversation_id = :toConversationId WHERE conversation_id = :fromConversationId")
    suspend fun reparentConversationId(fromConversationId: Long, toConversationId: Long)

    // v1.17.0 — Param `state` typé enum (était Int). TypeConverter [MessageEnumConverters]
    // convertit en Int pour le binding SQL. Cohérence avec MessageDao.updateStatus.
    @Query("UPDATE scheduled_messages SET state = :state WHERE id = :id")
    suspend fun setState(id: Long, state: ScheduledState)

    /**
     * v1.26.1 (audit H6) — revendication ATOMIQUE `PENDING → SENDING`.
     *
     * Rend le nombre de lignes réellement modifiées : `1` = cette exécution a pris l'envoi,
     * `0` = quelqu'un d'autre l'a déjà pris (ou l'état n'est plus `PENDING`). Sans elle, la
     * séquence était « lire l'état, envoyer, écrire SENT » : si le processus mourait entre
     * l'envoi réussi et l'écriture — tueur OEM, OOM, force-stop — WorkManager ré-exécutait le
     * travail, retrouvait l'état `PENDING` et **renvoyait le message**. Deux SMS reçus, deux
     * facturés, deux bulles.
     *
     * Les états sont sérialisés en Int par [ScheduledState] : 0 = PENDING, 4 = SENDING.
     */
    @Query("UPDATE scheduled_messages SET state = 4 WHERE id = :id AND state = 0")
    suspend fun claimForSending(id: Long): Int

    /**
     * v1.26.1 (audit B2) — annulation CONDITIONNELLE, symétrique de [claimForSending].
     *
     * Rend `1` si l'annulation a réellement pris, `0` si l'envoi avait déjà été revendiqué par
     * une exécution du worker (état `SENDING`) ou s'il était déjà réglé. C'est ce verdict qui
     * autorise — ou non — la suppression des pièces jointes : les effacer pendant que le worker
     * lit encore les fichiers produisait un PDU construit sur un fichier absent ou tronqué, donc
     * un MMS parti amputé ou une partie de zéro octet écrite dans `content://mms`.
     *
     * `0` = PENDING, `3` = CANCELLED.
     */
    @Query("UPDATE scheduled_messages SET state = 3 WHERE id = :id AND state = 0")
    suspend fun cancelIfPending(id: Long): Int

    @Query("UPDATE scheduled_messages SET work_id = :workId WHERE id = :id")
    suspend fun setWorkId(id: Long, workId: String?)

    /**
     * v1.25.3 (audit H6) — réarme un envoi abandonné : retour en `PENDING` (`state = 0`) ET
     * nouvelle échéance. Les deux writes dans le même UPDATE, sinon un envoi peut redevenir
     * éligible avec une échéance encore dans le passé, que [observePending] trierait en tête
     * avec une date périmée à l'écran.
     */
    @Query("UPDATE scheduled_messages SET state = 0, scheduled_at = :scheduledAt WHERE id = :id")
    suspend fun rearmPending(id: Long, scheduledAt: Long)

    @Query("DELETE FROM scheduled_messages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM scheduled_messages WHERE state = 0")
    suspend fun allPending(): List<ScheduledMessageEntity>
}
