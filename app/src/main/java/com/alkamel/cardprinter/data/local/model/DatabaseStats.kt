package com.alkamel.cardprinter.data.local.model

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
