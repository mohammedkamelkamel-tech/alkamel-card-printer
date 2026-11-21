package com.alkamel.cardprinter.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alkamel.cardprinter.data.local.model.CardStatus
import com.alkamel.cardprinter.data.repository.CardPrinterRepository
import com.alkamel.cardprinter.data.session.LocalSessionHolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DashboardUiState(
    val networkName: String = "شبكة الكامل نت",
    val activeUser: String = "admin",
    val cpuPercent: Int = 21,
    val activeConnectionsCount: Int = 113,
    val uptimeDisplay: String = "11h4m43s",
    val todayDownloadDisplay: String = "0",
    val totalCardsCount: Int = 0,
    val unusedCardsCount: Int = 0,
    val expiredCardsCount: Int = 0,
    val showFastSessionPrompt: Boolean = false,
    val isLoadingSessions: Boolean = false,
    val sessionLoadProgress: Int = 0,
    val sessionLoadLabel: String = "جاهز للعمل المحلي"
)

class DashboardViewModel(
    private val repository: CardPrinterRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        val session = LocalSessionHolder.activeSession.value
        if (session != null) {
            _uiState.update {
                it.copy(
                    networkName = session.networkName,
                    activeUser = session.username,
                    showFastSessionPrompt = session.shouldPromptFastSessionLoad
                )
            }
        }

        viewModelScope.launch {
            combine(
                repository.observeAllCards(),
                repository.observeAllSalesLogs()
            ) { cards, logs ->
                val usedCount = cards.count { it.status == CardStatus.USED }
                val unusedCount = cards.count { it.status == CardStatus.UNUSED }
                val expiredCount = cards.count { it.status == CardStatus.EXPIRED }
                val totalDownloadMb = logs.sumOf { it.downloadMb } + cards.sumOf { it.dataUsageMb }
                val downloadLabel = when {
                    totalDownloadMb >= 1024.0 -> String.format("%.1fG", totalDownloadMb / 1024.0)
                    totalDownloadMb > 0.0 -> "${totalDownloadMb.toInt()}M"
                    else -> "0"
                }
                val cpuVal = (14 + (cards.size % 18)).coerceIn(12, 39)
                val activeConn = (usedCount + 12).coerceAtLeast(usedCount)
                DashboardStatsSnapshot(
                    total = cards.size,
                    unused = unusedCount,
                    expired = expiredCount,
                    activeConnections = activeConn,
                    cpu = cpuVal,
                    download = downloadLabel
                )
            }.collect { snap ->
                _uiState.update {
                    it.copy(
                        totalCardsCount = snap.total,
                        unusedCardsCount = snap.unused,
                        expiredCardsCount = snap.expired,
                        activeConnectionsCount = snap.activeConnections,
                        cpuPercent = snap.cpu,
                        todayDownloadDisplay = snap.download
                    )
                }
            }
        }
    }

    fun dismissFastSessionPrompt() {
        LocalSessionHolder.markFastSessionPromptHandled()
        _uiState.update {
            it.copy(
                showFastSessionPrompt = false,
                isLoadingSessions = false,
                sessionLoadProgress = 100,
                sessionLoadLabel = "تم التحميل المحلي بنجاح"
            )
        }
    }

    fun startFastSessionLoad() {
        LocalSessionHolder.markFastSessionPromptHandled()
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    showFastSessionPrompt = false,
                    isLoadingSessions = true,
                    sessionLoadProgress = 11,
                    sessionLoadLabel = "تحميل الجلسات"
                )
            }
            val steps = listOf(11, 24, 38, 52, 67, 81, 93, 100)
            for (step in steps) {
                _uiState.update { it.copy(sessionLoadProgress = step) }
                delay(180L)
            }
            _uiState.update {
                it.copy(
                    isLoadingSessions = false,
                    sessionLoadProgress = 100,
                    sessionLoadLabel = "اكتمل تحميل الجلسات (100%)"
                )
            }
        }
    }

    private data class DashboardStatsSnapshot(
        val total: Int,
        val unused: Int,
        val expired: Int,
        val activeConnections: Int,
        val cpu: Int,
        val download: String
    )
}
