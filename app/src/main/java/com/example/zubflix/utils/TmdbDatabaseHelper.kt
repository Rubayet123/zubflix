package com.example.zubflix.utils

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class TmdbDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "tmdb_cache.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_NAME = "tmdb_items"
        
        private const val COL_ID = "id"
        private const val COL_QUERY_KEY = "query_key" // e.g. "title_year_type"
        private const val COL_JSON = "json_data"
        private const val COL_TIMESTAMP = "timestamp"

        private const val CREATE_TABLE = """
            CREATE TABLE $TABLE_NAME (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_QUERY_KEY TEXT UNIQUE,
                $COL_JSON TEXT,
                $COL_TIMESTAMP LONG
            )
        """
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_TABLE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }

    fun saveItem(queryKey: String, jsonData: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_QUERY_KEY, queryKey)
            put(COL_JSON, jsonData)
            put(COL_TIMESTAMP, System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getItem(queryKey: String): String? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_NAME,
            arrayOf(COL_JSON),
            "$COL_QUERY_KEY = ?",
            arrayOf(queryKey),
            null, null, null
        )
        
        var result: String? = null
        if (cursor.moveToFirst()) {
            result = cursor.getString(cursor.getColumnIndexOrThrow(COL_JSON))
        }
        cursor.close()
        return result
    }

    fun clearAll() {
        writableDatabase.delete(TABLE_NAME, null, null)
    }
}
