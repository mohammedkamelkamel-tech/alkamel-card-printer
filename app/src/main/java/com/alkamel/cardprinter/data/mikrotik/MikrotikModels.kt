package com.alkamel.cardprinter.data.mikrotik

import com.alkamel.cardprinter.data.local.model.PeriodSalesSummary

data class MikrotikCredentials(
    val host: String,
    val port: Int,
    val username: String,
    val password: String
)

data class MikrotikSystemStatus(
    val cpuLoadPercent: Int,
    val activeConnectionsCount: Int,
    val uptimeDisplay: String,
    val totalDownloadBytes: Long,
    val totalDownloadDisplay: String,
    val routerOsVersion: String,
    val boardName: String,
    val fetchedAtMillis: Long = System.currentTimeMillis()
)

data class MikrotikConnectionTestResult(
    val isConnected: Boolean,
    val systemStatus: MikrotikSystemStatus? = null,
    val errorMessage: String? = null
)

data class MikrotikSyncSummary(
    val systemStatus: MikrotikSystemStatus,
    val packagesSynced: Int,
    val cardsSynced: Int,
    val activeSessionsCount: Int
)

data class MikrotikPushCardProgress(
    val processedCount: Int,
    val addedToRouterCount: Int,
    val failedCount: Int,
    val totalCount: Int,
    val percent: Int
)

/**
 * Live metrics aggregated directly from MikroTik sessions, active connections,
 * and payments (RouterOS v6 & v7) for the Reports & Statistics screen.
 */
data class MikrotikLiveReportsMetrics(
    val todayDownloadBytes: Long = 0L,
    val activeLiveDownloadBytes: Long = 0L,
    val todaySales: PeriodSalesSummary = PeriodSalesSummary(),
    val yesterdaySales: PeriodSalesSummary = PeriodSalesSummary(),
    val weeklySales: PeriodSalesSummary = PeriodSalesSummary(),
    val monthlySales: PeriodSalesSummary = PeriodSalesSummary(),
    val sessionsScannedCount: Int = 0,
    val paymentsScannedCount: Int = 0,
    val sourceNote: String = "",
    val fetchedAtMillis: Long = System.currentTimeMillis()
)
