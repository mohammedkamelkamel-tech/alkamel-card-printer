package com.alkamel.cardprinter.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.alkamel.cardprinter.data.local.entity.PrintSalesLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PrintSalesLogDao {

    @Query("SELECT * FROM print_sales_logs ORDER BY createdAt DESC")
    fun observeAllSalesLogs(): Flow<List<PrintSalesLogEntity>>

    @Query("SELECT * FROM print_sales_logs WHERE dateKey BETWEEN :fromDateKey AND :toDateKey ORDER BY createdAt DESC")
    fun observeSalesLogsBetween(fromDateKey: String, toDateKey: String): Flow<List<PrintSalesLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSalesLog(log: PrintSalesLogEntity): Long

    @Query("DELETE FROM print_sales_logs WHERE id = :logId")
    suspend fun deleteSalesLogById(logId: Long)
}
