package com.example.accountability.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "accountability_records")
data class AccountabilityEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: String,
    val socksStatus: String,
    val towelStatus: String,
    val booksStatus: String,
    val rawTranscript: String,
    val isSynced: Boolean = false
)
