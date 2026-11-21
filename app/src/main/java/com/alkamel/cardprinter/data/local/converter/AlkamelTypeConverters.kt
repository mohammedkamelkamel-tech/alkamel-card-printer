package com.alkamel.cardprinter.data.local.converter

import androidx.room.TypeConverter
import com.alkamel.cardprinter.data.local.model.CardStatus
import com.alkamel.cardprinter.data.local.model.NetworkLoadType
import com.alkamel.cardprinter.data.local.model.PrintMethod
import com.alkamel.cardprinter.data.local.model.SaleOperationType

class AlkamelTypeConverters {

    @TypeConverter
    fun fromNetworkLoadType(value: NetworkLoadType): String = value.name

    @TypeConverter
    fun toNetworkLoadType(value: String): NetworkLoadType =
        runCatching { NetworkLoadType.valueOf(value) }.getOrDefault(NetworkLoadType.LOAD_USER_MANAGER)

    @TypeConverter
    fun fromCardStatus(value: CardStatus): String = value.name

    @TypeConverter
    fun toCardStatus(value: String): CardStatus =
        runCatching { CardStatus.valueOf(value) }.getOrDefault(CardStatus.UNUSED)

    @TypeConverter
    fun fromPrintMethod(value: PrintMethod): String = value.name

    @TypeConverter
    fun toPrintMethod(value: String): PrintMethod =
        runCatching { PrintMethod.valueOf(value) }.getOrDefault(PrintMethod.PDF_A4_GRID)

    @TypeConverter
    fun fromSaleOperationType(value: SaleOperationType): String = value.name

    @TypeConverter
    fun toSaleOperationType(value: String): SaleOperationType =
        runCatching { SaleOperationType.valueOf(value) }.getOrDefault(SaleOperationType.PRINT_SALE)
}
