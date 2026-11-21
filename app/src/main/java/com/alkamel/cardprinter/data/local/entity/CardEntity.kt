package com.alkamel.cardprinter.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.alkamel.cardprinter.data.local.model.CardStatus

@Entity(
    tableName = "cards",
    indices = [
        Index(value = ["username"], unique = true),
        Index(value = ["packageName"]),
        Index(value = ["status"]),
        Index(value = ["printedDateKey"])
    ]
)
data class CardEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val username: String,
    val password: String = "",
    val packageId: Long? = null,
    val packageName: String = "",
    val price: Double = 0.0,
    val commission: Double = 0.0,
    val serverAccount: String = "",
    val mikrotikId: String? = null,
    val status: CardStatus = CardStatus.UNUSED,
    val isPrinted: Boolean = false,
    val printedAt: Long? = null,
    val printedDateKey: String? = null,
    val uptimeLabel: String = "0m",
    val uptimeMinutes: Long = 0L,
    val dataUsageLabel: String = "0 MB",
    val dataUsageMb: Double = 0.0,
    val bindFirstDevice: Boolean = false,
    val sourceFileName: String? = null,
    val batchId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
