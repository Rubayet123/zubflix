package com.example.zubflix.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(watchHistory: WatchHistoryEntity)
    
    @Query("SELECT * FROM watch_history ORDER BY lastWatchedTimestamp DESC LIMIT :limit")
    fun getRecentlyWatched(limit: Int = 20): Flow<List<WatchHistoryEntity>>
    
    @Query("SELECT * FROM watch_history ORDER BY lastWatchedTimestamp DESC LIMIT :limit")
    suspend fun getRecentlyWatchedOnce(limit: Int = 20): List<WatchHistoryEntity>

    @Query("SELECT * FROM watch_history WHERE itemId = :itemId LIMIT 1")
    suspend fun getWatchHistoryById(itemId: String): WatchHistoryEntity?
    
    @Query("DELETE FROM watch_history WHERE itemId NOT IN (SELECT itemId FROM watch_history ORDER BY lastWatchedTimestamp DESC LIMIT 50)")
    suspend fun trimExcessHistory()
    
    @Query("DELETE FROM watch_history WHERE itemId = :itemId")
    suspend fun deleteById(itemId: String)
    
    @Query("DELETE FROM watch_history")
    suspend fun clearAll()
}
