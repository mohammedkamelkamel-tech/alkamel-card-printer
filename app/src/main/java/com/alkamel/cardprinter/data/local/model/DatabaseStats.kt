package com.alkamel.cardprinter.data.local.model

data class CardCountsSummary(
    val totalCount: Int = 0,
    val unusedCount: Int = 0,
    val usedCount: Int = 0,
    val expiredCount: Int = 0,
    val totalDataUsageMb: Double = 0.0
)

data class PackageCardCount(
    val packageName: String,
    val cardsCount: Int
)

data class CardSyncMeta(
    val id: Long,
    val username: String,
    val price: Double,
    val isPrinted: Boolean,
    val printedAt: Long?,
    val printedDateKey: String?,
    val createdAt: Long,
    val mikrotikId: String?,
    val sourceFileName: String?
)

data class PeriodSalesSummary(
    val totalAmount: Double = 0.0,
    val totalCards: Int = 0,
    val operationsCount: Int = 0
)

data class TopSellingPackageStat(
    val packageName: String,
    val totalCards: Int,
    val totalAmount: Double
)

data class ComprehensiveSalesReport(
    val today: PeriodSalesSummary = PeriodSalesSummary(),
    val yesterday: PeriodSalesSummary = PeriodSalesSummary(),
    val weekly: PeriodSalesSummary = PeriodSalesSummary(),
    val monthly: PeriodSalesSummary = PeriodSalesSummary(),
    val allTime: PeriodSalesSummary = PeriodSalesSummary(),
    val topSellingPackage: TopSellingPackageStat? = null
)
