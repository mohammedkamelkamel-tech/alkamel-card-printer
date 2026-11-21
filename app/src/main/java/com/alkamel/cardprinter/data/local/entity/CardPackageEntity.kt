package com.alkamel.cardprinter.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "card_packages",
    indices = [Index(value = ["name"], unique = true)]
)
data class CardPackageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val price: Double = 100.0,
    val commission: Double = 0.0,
    val pointOfSale: String = "ether3",
    val validityLabel: String = "",
    val dataLimitMb: Double = 0.0,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
