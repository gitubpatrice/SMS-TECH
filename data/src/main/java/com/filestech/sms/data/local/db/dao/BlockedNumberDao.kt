package com.filestech.sms.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.filestech.sms.data.local.db.entity.BlockedNumberEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedNumberDao {

    @Query("SELECT * FROM blocked_numbers ORDER BY created_at DESC")
    fun observe(): Flow<List<BlockedNumberEntity>>

    @Query("SELECT * FROM blocked_numbers WHERE normalized_number = :normalized LIMIT 1")
    suspend fun findByNormalized(normalized: String): BlockedNumberEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_numbers WHERE normalized_number = :normalized)")
    suspend fun isBlocked(normalized: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BlockedNumberEntity): Long

    @Query("DELETE FROM blocked_numbers WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM blocked_numbers WHERE normalized_number = :normalized")
    suspend fun deleteByNormalized(normalized: String)

    /** Snapshot of every blocked normalized number — used to filter SMS imports + UI lists. */
    @Query("SELECT normalized_number FROM blocked_numbers")
    suspend fun allNormalized(): List<String>

    /** v1.27.2 (audit Codex, C-08) — formes brutes, seules porteuses de l'indicatif pays. */
    @Query("SELECT raw_number FROM blocked_numbers")
    suspend fun allRaw(): List<String>

    /**
     * v1.27.2 (audit Codex, C-08) — TOUTES les entrees d'un meme seau, pas la premiere.
     * Deux correspondants distincts peuvent partager une cle de neuf chiffres ; le tri final se
     * fait sur la forme brute.
     */
    @Query("SELECT * FROM blocked_numbers WHERE normalized_number = :normalized")
    suspend fun findAllByNormalized(normalized: String): List<BlockedNumberEntity>

    /**
     * v1.25.4 — instantané complet, `raw_number` compris.
     *
     * Requis par `BlockedNumbersImporter.rekeyLegacyEntries()` : recalculer la clé d'une entrée
     * héritée suppose de repartir de la forme **brute** saisie à l'origine, la clé enregistrée
     * ayant déjà perdu ce qui permet de la recalculer (les lettres, notamment).
     */
    @Query("SELECT * FROM blocked_numbers")
    suspend fun all(): List<BlockedNumberEntity>
}
