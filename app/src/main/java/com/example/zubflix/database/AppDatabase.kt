package com.example.zubflix.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [WatchHistoryEntity::class, MyListEntity::class, SearchHistoryEntity::class], version = 7, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun myListDao(): MyListDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "zubflix_database"
                )
                .fallbackToDestructiveMigration() // For development - recreates DB on schema change
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
