package com.clipboardspeech.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [HistoryEntry::class, ProcessNoteEntry::class], version = 3)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun processNoteDao(): ProcessNoteDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE history ADD COLUMN aiSummary TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS process_note_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        contentPreview TEXT NOT NULL,
                        status TEXT NOT NULL,
                        serverMessage TEXT,
                        inboxFile TEXT,
                        createdAt INTEGER NOT NULL,
                        completedAt INTEGER
                    )
                """.trimIndent())
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context, AppDatabase::class.java, "clipboard_speech.db")
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build().also { INSTANCE = it }
            }
    }
}
