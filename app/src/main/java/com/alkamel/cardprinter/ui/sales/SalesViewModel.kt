package com.alkamel.cardprinter.ui.sales

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alkamel.cardprinter.data.local.entity.PrintSalesLogEntity
import com.alkamel.cardprinter.data.local.util.DateTimeUtils
import com.alkamel.cardprinter.data.repository.CardPrinterRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SalesUiState(
    val fromDate: String = DateTimeUtils.monthStartDateKey(),
    val toDate: String = DateTimeUtils.todayDateKey(),
    val isShowAllMode: Boolean = true,
    val allLogs: List<PrintSalesLogEntity> = emptyList(),
    val visibleLogs: List<PrintSalesLogEntity> = emptyList(),
    val totalAmount: Double = 0.0,
    val totalCardsCount: Int = 0
)

class SalesViewModel(
    private val repository: CardPrinterRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SalesUiState())
    val uiState: StateFlow<SalesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAllSalesLogs().collect { logs ->
                _uiState.update { current ->
                    val next = current.copy(allLogs = logs)
                    recalculate(next)
                }
            }
        }
    }

    fun onFromDateChange(value: String) = _uiState.update { it.copy(fromDate = value) }
    fun onToDateChange(value: String) = _uiState.update { it.copy(toDate = value) }

    fun applyDateRangeSearch() {
        _uiState.update { current ->
            recalculate(current.copy(isShowAllMode = false))
        }
    }

    fun showAllSales() {
        _uiState.update { current ->
            recalculate(current.copy(isShowAllMode = true))
        }
    }

    fun deleteSaleLog(logId: Long) {
        viewModelScope.launch {
            repository.deleteSalesLog(logId)
        }
    }

    private fun recalculate(state: SalesUiState): SalesUiState {
        val filtered = if (state.isShowAllMode) {
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
        return state.copy(
            visibleLogs = filtered,
            totalAmount = filtered.sumOf { it.totalAmount },
            totalCardsCount = filtered.sumOf { it.cardsCount }
        )
    }
}
