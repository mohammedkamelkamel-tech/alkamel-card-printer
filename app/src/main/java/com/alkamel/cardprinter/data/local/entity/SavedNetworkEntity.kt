package com.alkamel.cardprinter.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.alkamel.cardprinter.data.local.model.NetworkLoadType

@Entity(tableName = "saved_networks")
data class SavedNetworkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val networkName: String,
    val hostAddress: String,
    val port: Int = 8728,
    val username: String = "admin",
    val password: String = "",
    val loadType: NetworkLoadType = NetworkLoadType.LOAD_USER_MANAGER,
    val isDefault: Boolean = false,
    val lastUsedAt: Long = System.currentTimeMillis()
)
