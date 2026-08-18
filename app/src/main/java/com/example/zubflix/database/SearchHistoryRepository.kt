package com.example.zubflix.database

import kotlinx.coroutines.flow.Flow

class SearchHistoryRepository(private val searchHistoryDao: SearchHistoryDao) {

    fun getRecentSearches(limit: Int = 20): Flow<List<SearchHistoryEntity>> {
        return searchHistoryDao.getRecentSearches(limit)
    }

    suspend fun saveQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) {
            searchHistoryDao.insertSearch(SearchHistoryEntity(query = trimmed, timestamp = System.currentTimeMillis()))
        }
    }

    suspend fun deleteQuery(query: String) {
        searchHistoryDao.deleteSearch(query.trim())
    }

    suspend fun clearHistory() {
        searchHistoryDao.clearAllHistory()
    }
}
