package com.filestech.sms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "blocked_numbers",
    indices = [Index(value = ["normalized_number"], unique = true)],
)
data class BlockedNumberEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "normalized_number") val normalizedNumber: String,
    @ColumnInfo(name = "raw_number") val rawNumber: String,
    @ColumnInfo(name = "label") val label: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** mirrors BlockedNumberContract row when synced */
    @ColumnInfo(name = "system_uri") val systemUri: String? = null,
)
