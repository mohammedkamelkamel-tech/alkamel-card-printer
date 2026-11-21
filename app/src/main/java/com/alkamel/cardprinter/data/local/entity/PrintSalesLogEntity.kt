package com.alkamel.cardprinter.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.alkamel.cardprinter.data.local.model.PrintMethod
import com.alkamel.cardprinter.data.local.model.SaleOperationType

@Entity(
    tableName = "print_sales_logs",
    indices = [Index(value = ["dateKey"]), Index(value = ["packageName"])]
)
data class PrintSalesLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val cardUsername: String,
    val packageName: String,
    val cardsCount: Int = 1,
    val unitPrice: Double = 0.0,
    val totalAmount: Double = 0.0,
    val commissionTotal: Double = 0.0,
    val serverAccount: String = "",
    val downloadLabel: String = "0 MB",
    val downloadMb: Double = 0.0,
    val printMethod: PrintMethod = PrintMethod.PDF_A4_GRID,
    val operationType: SaleOperationType = SaleOperationType.PRINT_SALE,
    val dateKey: String,
    val createdAt: Long = System.currentTimeMillis()
)
