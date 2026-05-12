package com.clipboardspeech.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY savedAt DESC LIMIT 50")
    fun getAll(): LiveData<List<HistoryEntry>>

    @Query("SELECT * FROM history ORDER BY savedAt DESC LIMIT 3")
    suspend fun getRecent(): List<HistoryEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: HistoryEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAndGetId(entry: HistoryEntry): Long

    @Delete
    suspend fun delete(entry: HistoryEntry)

    @Query("DELETE FROM history")
    suspend fun deleteAll()

    @Query("SELECT * FROM history WHERE id = :id")
    suspend fun getById(id: Long): HistoryEntry?

    @Query("SELECT * FROM history WHERE fullText = :text LIMIT 1")
    suspend fun findByText(text: String): HistoryEntry?

    @Query("UPDATE history SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE history SET aiSummary = :summary WHERE id = :id")
    suspend fun updateAiSummary(id: Long, summary: String)
}
