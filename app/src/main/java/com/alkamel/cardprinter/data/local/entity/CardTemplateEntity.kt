package com.alkamel.cardprinter.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "card_templates")
data class CardTemplateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val packageName: String = "الكل",
    val backgroundImagePath: String? = null,
    val cardWidth: Float = 203f,
    val cardHeight: Float = 61f,
    val columnsPerPage: Int = 4,
    val rowsPerPage: Int = 17,
    val showUsername: Boolean = true,
    val showPassword: Boolean = false,
    val showQrCode: Boolean = false,
    val usernameX: Float = 0.50f,
    val usernameY: Float = 0.52f,
    val passwordX: Float = 0.50f,
    val passwordY: Float = 0.78f,
    val qrX: Float = 0.84f,
    val qrY: Float = 0.52f,
    val textColorHex: String = "#FFFFFF",
    val fontSizeSp: Float = 16f,
    val isDefault: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)
