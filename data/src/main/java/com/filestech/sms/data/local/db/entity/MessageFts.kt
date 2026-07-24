package com.filestech.sms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.PrimaryKey

@Fts4(
    contentEntity = MessageEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
)
@Entity(tableName = "messages_fts")
data class MessageFts(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    @ColumnInfo(name = "body") val body: String,
    @ColumnInfo(name = "address") val address: String,
)
