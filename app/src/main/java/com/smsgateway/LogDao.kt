package com.smsgateway

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: LogEntry): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<LogEntry>): List<Long>

    @Query("SELECT * FROM logs ORDER BY id DESC")
    suspend fun getAll(): List<LogEntry>

    @Query("SELECT * FROM logs ORDER BY id DESC")
    fun getAllSync(): List<LogEntry>

    @Query("SELECT * FROM logs WHERE message LIKE '%' || :query || '%' OR formatted LIKE '%' || :query || '%' ORDER BY id DESC")
    suspend fun search(query: String): List<LogEntry>

    @Query("DELETE FROM logs WHERE message LIKE '%' || :query || '%' OR formatted LIKE '%' || :query || '%'")
    suspend fun deleteContaining(query: String): Int

    @Query("DELETE FROM logs")
    suspend fun clearAll()

    @Query("DELETE FROM logs WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteOlderThan(cutoffTimestamp: Long): Int

    @Query("DELETE FROM logs WHERE id NOT IN (SELECT id FROM logs ORDER BY id DESC LIMIT :maxRows)")
    suspend fun trimToMaxRows(maxRows: Int): Int

    @Query("SELECT COUNT(*) FROM logs")
    suspend fun count(): Int
}

