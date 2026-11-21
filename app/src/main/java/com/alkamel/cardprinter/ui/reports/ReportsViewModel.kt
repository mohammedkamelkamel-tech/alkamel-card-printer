package com.alkamel.cardprinter.ui.reports

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alkamel.cardprinter.data.local.entity.PrintSalesLogEntity
import com.alkamel.cardprinter.data.local.model.CardStatus
import com.alkamel.cardprinter.data.local.model.ComprehensiveSalesReport
import com.alkamel.cardprinter.data.local.util.DateTimeUtils
import com.alkamel.cardprinter.data.printing.PdfPrintEngine
import com.alkamel.cardprinter.data.printing.PdfResult
import com.alkamel.cardprinter.data.repository.CardPrinterRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ReportsUiState(
    val report: ComprehensiveSalesReport = ComprehensiveSalesReport(),
    val allLogs: List<PrintSalesLogEntity> = emptyList(),
    val totalCards: Int = 0,
    val expiredCardsCount: Int = 0,
    val usedCardsCount: Int = 0,
    val unusedCardsCount: Int = 0,
    val expiredPercent: Int = 0,
    val usedPercent: Int = 0,
    val todayDownloadPercent: Int = 0,
    val todayDownloadGb: Double = 0.0,
    val totalDownloadGb: Double = 0.0,
    val isExportingPdf: Boolean = false,
    val lastExportedPdf: File? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

class ReportsViewModel(
    private val repository: CardPrinterRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReportsUiState())
    val uiState: StateFlow<ReportsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                repository.observeComprehensiveSalesReport(),
                repository.observeAllCards(),
                repository.observeAllSalesLogs()
            ) { report, cards, logs ->
                Triple(report, cards, logs)
            }.collect { (report, cards, logs) ->
                val total = cards.size.coerceAtLeast(1)
                val expired = cards.count { it.status == CardStatus.EXPIRED }
                val used = cards.count { it.status == CardStatus.USED }
                val unused = cards.count { it.status == CardStatus.UNUSED }

                val todayKey = DateTimeUtils.todayDateKey()
                val todayMb = logs.filter { it.dateKey == todayKey }.sumOf { it.downloadMb }
                val allMb = logs.sumOf { it.downloadMb } + cards.sumOf { it.dataUsageMb }
                val todayPercent = if (allMb > 0.0) ((todayMb / allMb) * 100.0).toInt().coerceIn(0, 100) else 0

                _uiState.update {
                    it.copy(
                        report = report,
                        allLogs = logs,
                        totalCards = cards.size,
                        expiredCardsCount = expired,
                        usedCardsCount = used,
                        unusedCardsCount = unused,
                        expiredPercent = ((expired * 100) / total).coerceIn(0, 100),
                        usedPercent = ((used * 100) / total).coerceIn(0, 100),
                        todayDownloadPercent = todayPercent,
                        todayDownloadGb = todayMb / 1024.0,
                        totalDownloadGb = allMb / 1024.0
                    )
                }
            }
        }
    }

    fun clearMessages() = _uiState.update { it.copy(statusMessage = null, errorMessage = null) }

    fun exportSalesReportPdf(context: Context, onPdfReady: (File) -> Unit) {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isExportingPdf = true, errorMessage = null) }
            val result = PdfPrintEngine.generateSalesReportPdf(
                context = context,
                fromDateKey = DateTimeUtils.monthStartDateKey(),
                toDateKey = DateTimeUtils.todayDateKey(),
                logs = state.allLogs,
                totalAmount = state.report.allTime.totalAmount,
                totalCards = state.report.allTime.totalCards,
                topPackage = state.report.topSellingPackage?.packageName ?: "غير محدد"
            )
            when (result) {
                is PdfResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isExportingPdf = false,
                            lastExportedPdf = result.file,
                            statusMessage = "تم إنشاء تقرير المبيعات PDF (A4) بنجاح."
                        )
                    }
                    onPdfReady(result.file)
                }
                is PdfResult.Error -> {
                    _uiState.update {
                        it.copy(isExportingPdf = false, errorMessage = result.message)
                    }
                }
            }
        }
    }
}
