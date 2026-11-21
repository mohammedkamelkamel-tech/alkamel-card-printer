package com.alkamel.cardprinter.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.alkamel.cardprinter.data.local.entity.SavedNetworkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedNetworkDao {

    @Query("SELECT * FROM saved_networks ORDER BY lastUsedAt DESC")
    fun observeAllNetworks(): Flow<List<SavedNetworkEntity>>

    @Query("SELECT COUNT(*) FROM saved_networks")
    suspend fun getCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNetwork(network: SavedNetworkEntity): Long

    @Delete
    suspend fun deleteNetwork(network: SavedNetworkEntity)
}
