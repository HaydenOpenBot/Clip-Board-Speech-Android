package com.clipboardspeech.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ProcessNoteDao {

    @Insert
    suspend fun insert(entry: ProcessNoteEntry): Long

    @Query("UPDATE process_note_log SET status = :status, serverMessage = :serverMessage, inboxFile = :inboxFile, completedAt = :completedAt WHERE id = :id")
    suspend fun updateResult(id: Long, status: String, serverMessage: String?, inboxFile: String?, completedAt: Long)

    @Query("SELECT * FROM process_note_log ORDER BY createdAt DESC LIMIT 50")
    fun getAll(): LiveData<List<ProcessNoteEntry>>
}
