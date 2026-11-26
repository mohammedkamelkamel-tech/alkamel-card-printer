package com.alkamel.cardprinter.ui.topsales

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alkamel.cardprinter.data.local.entity.PrintSalesLogEntity
import com.alkamel.cardprinter.data.local.model.DailyTopPackagesGroup
import com.alkamel.cardprinter.data.local.model.TopSalesAnalytics
import com.alkamel.cardprinter.data.local.model.VlanDataUsageRankingResult
import com.alkamel.cardprinter.data.local.util.DateTimeUtils
import com.alkamel.cardprinter.data.mikrotik.MikrotikSyncManager
import com.alkamel.cardprinter.data.repository.CardPrinterRepository
import com.alkamel.cardprinter.data.session.LocalSessionHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TopSalesTab {
    DAILY_TOP_PACKAGES,
    VLAN_DATA_USAGE
}

enum class TopSalesDatePreset {
    ALL,
    TODAY,
    LAST_7_DAYS,
    THIS_MONTH,
    CUSTOM
}

data class TopSalesUiState(
    val selectedTab: TopSalesTab = TopSalesTab.DAILY_TOP_PACKAGES,
    val datePreset: TopSalesDatePreset = TopSalesDatePreset.ALL,
    val fromDate: String = DateTimeUtils.monthStartDateKey(),
    val toDate: String = DateTimeUtils.todayDateKey(),
    val allLogs: List<PrintSalesLogEntity> = emptyList(),
    val filteredLogsCount: Int = 0,
    val dailyTopPackages: List<DailyTopPackagesGroup> = emptyList(),
    val totalDailyCards: Int = 0,
    val totalDailyAmount: Double = 0.0,
    val vlanRanking: VlanDataUsageRankingResult = VlanDataUsageRankingResult(),
    val isConnectedToMikrotik: Boolean = false,
    val isSyncing: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

class TopSalesViewModel(
    private val repository: CardPrinterRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TopSalesUiState())
    val uiState: StateFlow<TopSalesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                LocalSessionHolder.activeSession,
                repository.observeAllSalesLogs()
            ) { session, logs ->
                (session?.isConnectedToMikrotik == true) to logs
            }.collect { (connected, logs) ->
                _uiState.update { current ->
                    recalculate(
                        current.copy(
                            isConnectedToMikrotik = connected,
                            allLogs = logs
                        )
                    )
                }
            }
        }
    }

    fun selectTab(tab: TopSalesTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun selectPreset(preset: TopSalesDatePreset) {
        val today = DateTimeUtils.todayDateKey()
        _uiState.update { current ->
            val updated = when (preset) {
                TopSalesDatePreset.ALL -> current.copy(
                    datePreset = TopSalesDatePreset.ALL,
                    fromDate = DateTimeUtils.monthStartDateKey(),
                    toDate = today
                )
                TopSalesDatePreset.TODAY -> current.copy(
                    datePreset = TopSalesDatePreset.TODAY,
                    fromDate = today,
                    toDate = today
                )
                TopSalesDatePreset.LAST_7_DAYS -> current.copy(
                    datePreset = TopSalesDatePreset.LAST_7_DAYS,
                    fromDate = DateTimeUtils.daysAgoDateKey(6),
                    toDate = today
                )
                TopSalesDatePreset.THIS_MONTH -> current.copy(
                    datePreset = TopSalesDatePreset.THIS_MONTH,
                    fromDate = DateTimeUtils.monthStartDateKey(),
                    toDate = today
                )
                TopSalesDatePreset.CUSTOM -> current.copy(
                    datePreset = TopSalesDatePreset.CUSTOM
                )
            }
            recalculate(updated)
        }
    }

    fun onFromDateChange(value: String) {
        val clean = value.trim()
        _uiState.update { current ->
            val adjustedTo = if (clean.isNotBlank() && current.toDate.isNotBlank() && clean > current.toDate) {
                clean
            } else {
                current.toDate
            }
            recalculate(
                current.copy(
                    datePreset = TopSalesDatePreset.CUSTOM,
                    fromDate = clean,
                    toDate = adjustedTo
                )
            )
        }
    }

    fun onToDateChange(value: String) {
        val clean = value.trim()
        _uiState.update { current ->
            val adjustedFrom = if (clean.isNotBlank() && current.fromDate.isNotBlank() && clean < current.fromDate) {
                clean
            } else {
                current.fromDate
            }
            recalculate(
                current.copy(
                    datePreset = TopSalesDatePreset.CUSTOM,
                    fromDate = adjustedFrom,
                    toDate = clean
                )
            )
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
    }

    fun syncFromMikrotik() {
        val session = LocalSessionHolder.activeSession.value
        if (session == null || !session.hasConfiguredRouter) {
            _uiState.update { it.copy(errorMessage = "لا يوجد اتصال فعلي بسيرفر يوزر منجر.") }
            return
        }
        if (_uiState.value.isSyncing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, errorMessage = null) }
            val cards = repository.getAllCardsOnce()
            val packages = repository.getAllPackagesOnce()
            MikrotikSyncManager.fetchLiveReportsMetrics(
                credentials = session.toCredentials(),
                loadType = session.loadType,
                cards = cards,
                packages = packages,
                repository = repository
            ).onSuccess { metrics ->
                LocalSessionHolder.updateConnectionStatus(isConnected = true, errorMessage = null)
                LocalSessionHolder.updateLiveReportsMetrics(metrics)
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        statusMessage = "تم تحديث بيانات الأكثر مبيعات واستهلاك الفيلانات (${metrics.allTimeSales.totalCards} كرت)."
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        errorMessage = err.localizedMessage ?: "تعذر تحديث بيانات يوزر منجر من المايكروتيك."
                    )
                }
            }
        }
    }

    private fun recalculate(state: TopSalesUiState): TopSalesUiState {
        val scopedLogs = if (state.datePreset == TopSalesDatePreset.ALL) {
            state.allLogs
        } else {
            val from = state.fromDate.trim()
            val to = state.toDate.trim()
            state.allLogs.filter { log ->
                val afterFrom = from.isBlank() || log.dateKey >= from
                val beforeTo = to.isBlank() || log.dateKey <= to
                afterFrom && beforeTo
            }
        }

        val dailyGroups = TopSalesAnalytics.buildDailyTopPackages(scopedLogs)
        val vlanResult = TopSalesAnalytics.buildVlanDataUsageRanking(scopedLogs)

        return state.copy(
            filteredLogsCount = scopedLogs.size,
            dailyTopPackages = dailyGroups,
            totalDailyCards = dailyGroups.sumOf { it.totalCards },
            totalDailyAmount = dailyGroups.sumOf { it.totalAmount },
            vlanRanking = vlanResult
        )
    }
}
