package com.example.zubflix.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MyListDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: MyListEntity)

    @Query("DELETE FROM my_list WHERE itemId = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT EXISTS(SELECT 1 FROM my_list WHERE itemId = :id)")
    suspend fun isItemInList(id: String): Boolean

    @Query("SELECT * FROM my_list ORDER BY addedTimestamp DESC")
    suspend fun getAllItems(): List<MyListEntity>
}
