package com.clipboardspeech.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "process_note_log")
data class ProcessNoteEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contentPreview: String,       // first 120 chars
    val status: String,               // "pending" | "success" | "failed"
    val serverMessage: String? = null,
    val inboxFile: String? = null,
    val createdAt: Long,
    val completedAt: Long? = null
)
