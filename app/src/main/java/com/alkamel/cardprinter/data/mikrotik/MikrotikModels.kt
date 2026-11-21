package com.alkamel.cardprinter.data.mikrotik

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
