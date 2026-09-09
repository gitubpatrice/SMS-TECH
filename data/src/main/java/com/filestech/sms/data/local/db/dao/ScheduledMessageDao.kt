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
    @Query(
        """
        SELECT s.*, COALESCE(c.in_vault, 0) AS in_vault
          FROM scheduled_messages s
          LEFT JOIN conversations c ON c.id = s.conversation_id
         WHERE s.state IN (0, 4)
         ORDER BY s.scheduled_at ASC
        """,
    )
    fun observePending(): Flow<List<ScheduledWithVaultFlag>>

    /**
     * v1.25.3 (audit H6) — les envois abandonnés après épuisement des tentatives
     * ([com.filestech.sms.system.scheduler.ScheduledSendAttempt]). Sans cette requête ils
     * sortaient de [observePending] et disparaissaient de l'écran : l'utilisateur ne voyait ni
     * l'échec, ni le message. Les plus récents d'abord — c'est l'échec du jour qu'on vient voir.
     *
     * `state = 2` littéral pour la même raison que le `state = 0` ci-dessus : Room lie les
     * paramètres, pas les constantes, et l'enum est convertie par
     * [com.filestech.sms.data.local.db.MessageEnumConverters].
     *
     * v1.28.3 (F20) — inclut désormais `5` = `INTERRUPTED`. Un envoi revendiqué dont l'exécution
     * est morte n'apparaissait NULLE PART où on puisse agir sur lui : `observePending` le
     * montrait derrière un bouton « Annuler » incapable de le toucher, et cette liste-ci ne le
     * voyait pas. Les deux actions dont il a besoin — relancer, retirer — vivent ici.
     */
    @Query(
        """
        SELECT s.*, COALESCE(c.in_vault, 0) AS in_vault
          FROM scheduled_messages s
          LEFT JOIN conversations c ON c.id = s.conversation_id
         WHERE s.state IN (2, 5)
         ORDER BY s.scheduled_at DESC
        """,
    )
    fun observeFailed(): Flow<List<ScheduledWithVaultFlag>>

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
     *
     * v1.28.3 (F20) — la revendication **horodate** désormais son bail (`claimed_at`). La
     * condition, elle, ne bouge pas d'un iota : toujours `state = 0`, jamais de reprise d'une
     * ligne déjà revendiquée. Le bail ne sert pas à re-revendiquer — ce serait rouvrir le double
     * envoi que ce verrou a fermé — mais à savoir, plus tard, que l'exécution qui l'avait pris
     * n'existe plus. Cf. [markInterruptedIfStale].
     */
    @Query("UPDATE scheduled_messages SET state = 4, claimed_at = :now WHERE id = :id AND state = 0")
    suspend fun claimForSending(id: Long, now: Long): Int

    /**
     * v1.28.3 (F20) — conclut un envoi revendiqué dont le bail a expiré : `SENDING → INTERRUPTED`.
     *
     * Conditionnelle sur les deux critères à la fois, et c'est essentiel :
     *  - `state = 4` — on ne touche jamais un envoi déjà réglé ;
     *  - `claimed_at IS NULL OR claimed_at <= :cutoff` — on ne conclut pas un envoi que quelqu'un
     *    est peut-être en train de faire. `NULL` compte comme expiré : c'est le cas des lignes
     *    revendiquées par une version antérieure à la colonne, dont l'exécution est forcément
     *    morte puisque la base a été rouverte depuis.
     *
     * Rend le nombre de lignes modifiées, pour que l'appelant sache s'il a conclu ou non.
     */
    @Query(
        """
        UPDATE scheduled_messages
           SET state = 5
         WHERE id = :id
           AND state = 4
           AND (claimed_at IS NULL OR claimed_at <= :cutoff)
        """,
    )
    suspend fun markInterruptedIfStale(id: Long, cutoff: Long): Int

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
     *
     * v1.28.3 (F20) — efface aussi le bail. Un envoi qui redevient `PENDING` n'est revendiqué par
     * personne ; y laisser la date de l'ancienne revendication ferait mentir la colonne, et un
     * `claimed_at` déjà expiré à l'instant même où la ligne serait re-revendiquée rendrait la
     * conclusion `INTERRUPTED` possible avant même le premier envoi.
     */
    @Query("UPDATE scheduled_messages SET state = 0, scheduled_at = :scheduledAt, claimed_at = NULL WHERE id = :id")
    suspend fun rearmPending(id: Long, scheduledAt: Long)

    @Query("DELETE FROM scheduled_messages WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * v1.28.3 (F20) — tous les envois non réglés, **sans le filtre de visibilité du coffre**.
     *
     * Existait sous le nom `allPending` sans un seul appelant. Le filet de replanification du
     * démarrage ([com.filestech.sms.system.scheduler.ScheduledMessageSchedulerImpl.rescheduleAllPending])
     * lisait à sa place `observePending()`, c'est-à-dire le flux **destiné à l'écran** — et
     * depuis que celui-ci masque les envois du coffre tant que le second facteur n'a pas été
     * donné (v1.28.3, F02), un envoi programmé depuis une conversation protégée devenait
     * invisible au boot : si WorkManager avait perdu son job, plus rien ne le rattrapait. Une
     * règle d'affichage n'a pas à décider de ce qui part.
     *
     * `state IN (0, 4)` — `SENDING` compris : c'est justement la ligne revendiquée puis
     * abandonnée qu'il faut réveiller pour que le worker constate l'expiration de son bail et la
     * conclue.
     */
    @Query("SELECT * FROM scheduled_messages WHERE state IN (0, 4)")
    suspend fun allUnsettled(): List<ScheduledMessageEntity>

    /**
     * v1.28.3 (F03) — envois programmés rattachés à une conversation, **quel que soit leur
     * état**.
     *
     * `conversation_id` n'est pas une clé étrangère (cf. le KDoc de [reparentConversationId]),
     * et rien n'exigeait donc que la conversation parente survive. Une purge du coffre pouvait
     * ainsi laisser derrière elle un envoi programmé qui partait plus tard, avec son corps et
     * ses destinataires — après que l'application eut annoncé le coffre vide et retiré le PIN.
     *
     * Tous les états, et pas seulement `PENDING` : une ligne `SENDING` ou `FAILED` porte le même
     * contenu, et c'est le contenu qu'il s'agit de faire disparaître.
     */
    @Query("SELECT * FROM scheduled_messages WHERE conversation_id = :conversationId")
    suspend fun findForConversation(conversationId: Long): List<ScheduledMessageEntity>
}
