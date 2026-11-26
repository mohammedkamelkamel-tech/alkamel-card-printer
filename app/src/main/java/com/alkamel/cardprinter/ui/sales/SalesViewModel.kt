package com.alkamel.cardprinter.ui.sales

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alkamel.cardprinter.data.local.entity.PrintSalesLogEntity
import com.alkamel.cardprinter.data.local.entity.effectiveOperationsCount
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

enum class SalesFilterKind {
    ALL,
    VLAN,
    PACKAGE
}

data class SalesFilterOption(
    val kind: SalesFilterKind = SalesFilterKind.ALL,
    val value: String = "",
    val label: String = "الكل"
) {
    companion object {
        val ALL = SalesFilterOption(SalesFilterKind.ALL, "", "الكل")
    }
}

data class SalesUiState(
    val fromDate: String = DateTimeUtils.todayDateKey(),
    val toDate: String = DateTimeUtils.todayDateKey(),
    val isShowAllMode: Boolean = false,
    val hasExplicitDateFilter: Boolean = false,
    val selectedFilter: SalesFilterOption = SalesFilterOption.ALL,
    val availableFilters: List<SalesFilterOption> = listOf(SalesFilterOption.ALL),
    val allLogs: List<PrintSalesLogEntity> = emptyList(),
    val visibleLogs: List<PrintSalesLogEntity> = emptyList(),
    val totalAmount: Double = 0.0,
    val totalCardsCount: Int = 0,
    val totalOperationsCount: Int = 0,
    val isConnectedToMikrotik: Boolean = false,
    val isSyncing: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

class SalesViewModel(
    private val repository: CardPrinterRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SalesUiState())
    val uiState: StateFlow<SalesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                LocalSessionHolder.activeSession,
                repository.observeAllSalesLogs()
            ) { session, logs ->
                (session?.isConnectedToMikrotik == true) to logs
            }.collect { (connected, logs) ->
                _uiState.update { current ->
                    val today = DateTimeUtils.todayDateKey()
                    val effectiveShowAll = if (current.hasExplicitDateFilter) {
                        current.isShowAllMode
                    } else {
                        // Show today's sales by default (matching the reference view);
                        // automatically fall back to all dates if today has no records yet.
                        logs.isNotEmpty() && logs.none { it.dateKey == today }
                    }
                    val next = current.copy(
                        isConnectedToMikrotik = connected,
                        allLogs = logs,
                        isShowAllMode = effectiveShowAll
                    )
                    recalculate(next)
                }
            }
        }
    }

    fun onFromDateChange(value: String) {
        _uiState.update { current ->
            val clean = value.trim()
            val adjustedTo = if (clean.isNotBlank() && current.toDate.isNotBlank() && clean > current.toDate) {
                clean
            } else {
                current.toDate
            }
            recalculate(
                current.copy(
                    fromDate = clean,
                    toDate = adjustedTo,
                    isShowAllMode = false,
                    hasExplicitDateFilter = true
                )
            )
        }
    }

    fun onToDateChange(value: String) {
        _uiState.update { current ->
            val clean = value.trim()
            val adjustedFrom = if (clean.isNotBlank() && current.fromDate.isNotBlank() && clean < current.fromDate) {
                clean
            } else {
                current.fromDate
            }
            recalculate(
                current.copy(
                    fromDate = adjustedFrom,
                    toDate = clean,
                    isShowAllMode = false,
                    hasExplicitDateFilter = true
                )
            )
        }
    }

    fun onFilterSelected(option: SalesFilterOption) {
        _uiState.update { current ->
            recalculate(current.copy(selectedFilter = option))
        }
    }

    fun clearMessages() = _uiState.update { it.copy(statusMessage = null, errorMessage = null) }

    fun applyDateRangeSearch() {
        _uiState.update { current ->
            recalculate(
                current.copy(
                    isShowAllMode = false,
                    hasExplicitDateFilter = true
                )
            )
        }
    }

    fun showTodaySales() {
        val today = DateTimeUtils.todayDateKey()
        _uiState.update { current ->
            recalculate(
                current.copy(
                    fromDate = today,
                    toDate = today,
                    isShowAllMode = false,
                    hasExplicitDateFilter = true
                )
            )
        }
    }

    fun showAllSales() {
        _uiState.update { current ->
            recalculate(
                current.copy(
                    isShowAllMode = true,
                    hasExplicitDateFilter = true,
                    selectedFilter = SalesFilterOption.ALL
                )
            )
        }
    }

    fun syncSalesFromMikrotik() {
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
                        statusMessage = "تم تحديث مبيعات يوزر منجر (${metrics.todaySales.totalCards} اليوم • ${metrics.allTimeSales.totalCards} إجمالي)."
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        errorMessage = err.localizedMessage ?: "تعذر تحديث مبيعات يوزر منجر من المايكروتيك."
                    )
                }
            }
        }
    }

    fun deleteSaleLog(logId: Long) {
        viewModelScope.launch {
            repository.deleteSalesLog(logId)
        }
    }

    private fun recalculate(state: SalesUiState): SalesUiState {
        val dateFiltered = if (state.isShowAllMode) {
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

        // Build dropdown options from the current dataset (VLANs first, then Packages)
        val distinctVlans = state.allLogs
            .map { it.connectionVlan.trim() }
            .filter { it.isNotBlank() && it != "-" }
            .distinct()
            .sorted()
            .map { SalesFilterOption(SalesFilterKind.VLAN, it, it) }

        val distinctPackages = state.allLogs
            .map { it.packageName.trim() }
            .filter { it.isNotBlank() && it != "-" }
            .distinct()
            .sorted()
            .map { SalesFilterOption(SalesFilterKind.PACKAGE, it, it) }

        val options = buildList {
            add(SalesFilterOption.ALL)
            addAll(distinctVlans)
            addAll(distinctPackages)
        }

        val activeFilter = if (
            state.selectedFilter.kind == SalesFilterKind.ALL ||
            options.any { it.kind == state.selectedFilter.kind && it.value == state.selectedFilter.value }
        ) {
            state.selectedFilter
        } else {
            SalesFilterOption.ALL
        }

        val filtered = when (activeFilter.kind) {
            SalesFilterKind.ALL -> dateFiltered
            SalesFilterKind.VLAN -> dateFiltered.filter {
                it.connectionVlan.trim().equals(activeFilter.value, ignoreCase = true)
            }
            SalesFilterKind.PACKAGE -> dateFiltered.filter {
                it.packageName.trim().equals(activeFilter.value, ignoreCase = true)
            }
        }

        return state.copy(
            availableFilters = options,
            selectedFilter = activeFilter,
            visibleLogs = filtered,
            totalAmount = filtered.sumOf { it.totalAmount },
            totalCardsCount = filtered.sumOf { it.cardsCount },
            totalOperationsCount = filtered.sumOf { it.effectiveOperationsCount() }
        )
    }
}
