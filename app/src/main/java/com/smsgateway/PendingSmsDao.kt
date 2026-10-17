package com.smsgateway

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingSmsDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(messages: List<PendingSmsEntity>): List<Long>

    @Query("SELECT * FROM pending_sms ORDER BY queuedAt ASC LIMIT :limit")
    suspend fun getBatch(limit: Int = 10): List<PendingSmsEntity>

    @Query("SELECT * FROM pending_sms WHERE backendId = :backendId ORDER BY queuedAt ASC LIMIT :limit")
    suspend fun getBatchForBackend(backendId: String, limit: Int = 10): List<PendingSmsEntity>

    @Query("DELETE FROM pending_sms WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM pending_sms WHERE backendId = :backendId")
    suspend fun deleteByBackend(backendId: String)

    @Query("SELECT COUNT(*) FROM pending_sms")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM pending_sms WHERE backendId = :backendId")
    suspend fun countByBackend(backendId: String): Int
}

