package com.example.accountability.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountabilityDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: AccountabilityEntity): Long

    @Query("SELECT * FROM accountability_records ORDER BY id DESC")
    fun getAllRecords(): Flow<List<AccountabilityEntity>>

    @Query("SELECT * FROM accountability_records WHERE isSynced = 0 ORDER BY id ASC")
    suspend fun getPendingUnsyncedRecords(): List<AccountabilityEntity>

    @Query("UPDATE accountability_records SET isSynced = 1 WHERE id = :id")
    suspend fun markAsSynced(id: Long)

    @Query("SELECT COUNT(*) FROM accountability_records WHERE isSynced = 0")
    fun getPendingCount(): Flow<Int>
}
