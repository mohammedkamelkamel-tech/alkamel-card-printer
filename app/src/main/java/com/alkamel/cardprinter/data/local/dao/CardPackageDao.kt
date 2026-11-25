package com.alkamel.cardprinter.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.alkamel.cardprinter.data.local.entity.CardPackageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CardPackageDao {

    @Query("SELECT * FROM card_packages ORDER BY price ASC, name ASC")
    fun observeAllPackages(): Flow<List<CardPackageEntity>>

    @Query("SELECT COUNT(*) FROM card_packages")
    fun observePackageCount(): Flow<Int>

    @Query("SELECT * FROM card_packages")
    suspend fun getAllPackagesOnce(): List<CardPackageEntity>

    @Query("SELECT COUNT(*) FROM card_packages")
    suspend fun getCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPackage(pkg: CardPackageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPackagesIgnore(packages: List<CardPackageEntity>)

    @Delete
    suspend fun deletePackage(pkg: CardPackageEntity)
}
