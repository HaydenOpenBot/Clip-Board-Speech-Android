package com.clipboardspeech.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val fullText: String,
    val preview: String,
    val charCount: Int,
    val savedAt: Long,
    val aiSummary: String? = null
)
