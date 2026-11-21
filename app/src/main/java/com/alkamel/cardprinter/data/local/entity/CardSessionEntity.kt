package com.alkamel.cardprinter.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "card_sessions",
    indices = [Index(value = ["username"])]
)
data class CardSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val username: String,
    val mikrotikSessionId: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val uptime: String = "0s",
    val downloadBytes: Long = 0L,
    val uploadBytes: Long = 0L,
    val downloadLabel: String = "0 MB",
    val uploadLabel: String = "0 MB",
    val totalLabel: String = "0 MB",
    val userIp: String = "",
    val macAddress: String = "",
    val isActive: Boolean = false,
    val fetchedAt: Long = System.currentTimeMillis()
)
