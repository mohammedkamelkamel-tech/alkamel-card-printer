package com.alkamel.cardprinter.ui.reports

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alkamel.cardprinter.data.local.entity.CardPackageEntity
import com.alkamel.cardprinter.data.local.entity.PrintSalesLogEntity
import com.alkamel.cardprinter.data.local.model.CardCountsSummary
import com.alkamel.cardprinter.data.local.model.ComprehensiveSalesReport
import com.alkamel.cardprinter.data.local.model.PeriodSalesSummary
import com.alkamel.cardprinter.data.local.util.DateTimeUtils
import com.alkamel.cardprinter.data.mikrotik.MikrotikLiveReportsMetrics
import com.alkamel.cardprinter.data.mikrotik.MikrotikSyncManager
import com.alkamel.cardprinter.data.printing.PdfPrintEngine
import com.alkamel.cardprinter.data.printing.PdfResult
import com.alkamel.cardprinter.data.repository.CardPrinterRepository
import com.alkamel.cardprinter.data.session.ActiveSession
import com.alkamel.cardprinter.data.session.LocalSessionHolder
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
    val topPackageSubtitle: String = "",
    val dataSourceNote: String = "",
    val isConnectedToMikrotik: Boolean = false,
    val isRefreshingMikrotik: Boolean = false,
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

    private var latestPackages: List<CardPackageEntity> = emptyList()

    init {
        viewModelScope.launch {
            val localDataFlow = combine(
                repository.observeComprehensiveSalesReport(),
                repository.observeCardCounts(),
                repository.observeAllSalesLogs()
            ) { report, cardCounts, logs ->
                Triple(report, cardCounts, logs)
            }
            val metaDataFlow = combine(
                repository.observeAllPackages(),
                LocalSessionHolder.activeSession,
                LocalSessionHolder.liveReportsMetrics
            ) { packages, session, liveMetrics ->
                Triple(packages, session, liveMetrics)
            }
            combine(localDataFlow, metaDataFlow) { localTriple, metaTriple ->
                ReportsBundle(
                    localReport = localTriple.first,
                    cardCounts = localTriple.second,
                    logs = localTriple.third,
                    packages = metaTriple.first,
                    session = metaTriple.second,
                    liveMetrics = metaTriple.third
                )
            }.collect { bundle ->
                latestPackages = bundle.packages

                val counts = bundle.cardCounts
                val logs = bundle.logs
                val localReport = bundle.localReport
                val live = bundle.liveMetrics
                val connected = bundle.session?.isConnectedToMikrotik == true

                val totalCards = counts.totalCount
                val totalSafe = totalCards.coerceAtLeast(1)
                val expired = counts.expiredCount
                val used = counts.usedCount
                val unused = counts.unusedCount

                val todayKey = DateTimeUtils.todayDateKey()
                val localTodayMb = logs.filter { it.dateKey == todayKey }.sumOf { it.downloadMb }
                val liveTodayBytes = live?.todayDownloadBytes
                    ?: bundle.session?.systemStatus?.totalDownloadBytes
                    ?: 0L
                val liveTodayMb = liveTodayBytes / (1024.0 * 1024.0)
                val effectiveTodayMb = maxOf(localTodayMb, liveTodayMb)

                val cardsTotalMb = counts.totalDataUsageMb
                val logsTotalMb = logs.sumOf { it.downloadMb }
                val effectiveTotalMb = maxOf(cardsTotalMb, logsTotalMb, effectiveTodayMb)

                val todayPercent = when {
                    effectiveTotalMb > 0.0 && effectiveTodayMb > 0.0 -> {
                        val raw = ((effectiveTodayMb / effectiveTotalMb) * 100.0).toInt()
                        if (raw == 0) 1 else raw.coerceIn(1, 100)
                    }
                    else -> 0
                }

                fun mergePeriod(local: PeriodSalesSummary, remote: PeriodSalesSummary?): PeriodSalesSummary {
                    if (remote == null) return local
                    return when {
                        local.totalCards == 0 && remote.totalCards > 0 -> remote
                        remote.totalCards == 0 -> local
                        remote.totalAmount > local.totalAmount || remote.totalCards > local.totalCards -> remote
                        else -> local
                    }
                }

                val mergedToday = mergePeriod(localReport.today, live?.todaySales)
                val mergedYesterday = mergePeriod(localReport.yesterday, live?.yesterdaySales)
                val mergedWeekly = mergePeriod(localReport.weekly, live?.weeklySales)
                val mergedMonthly = mergePeriod(localReport.monthly, live?.monthlySales)
                val mergedAllTime = mergePeriod(localReport.allTime, live?.allTimeSales)

                val derivedTopPkg = localReport.topSellingPackage
                val topPkgSubtitle = if (derivedTopPkg != null) {
                    "${derivedTopPkg.totalCards} كرت • متبقي: $unused"
                } else {
                    "متبقي غير مستخدم: $unused"
                }

                val mergedReport = ComprehensiveSalesReport(
                    today = mergedToday,
                    yesterday = mergedYesterday,
                    weekly = mergedWeekly,
                    monthly = mergedMonthly,
                    allTime = if (mergedAllTime.totalCards > 0) mergedAllTime else mergedMonthly,
                    topSellingPackage = derivedTopPkg
                )

                val sourceNote = when {
                    live != null && live.sourceNote.isNotBlank() -> live.sourceNote
                    connected -> "بيانات حية من يوزر منجر ($totalCards كرت متزامن)"
                    totalCards > 0 -> "بيانات من قاعدة البيانات المحلية ($totalCards كرت محفوظ)"
                    else -> "لا توجد بيانات بعد — اتصل بالمايكروتيك وقم بالمزامنة"
                }

                _uiState.update {
                    it.copy(
                        report = mergedReport,
                        allLogs = logs,
                        totalCards = totalCards,
                        expiredCardsCount = expired,
                        usedCardsCount = used,
                        unusedCardsCount = unused,
                        expiredPercent = if (totalCards == 0) 0 else ((expired * 100) / totalSafe).coerceIn(0, 100),
                        usedPercent = if (totalCards == 0) 0 else ((used * 100) / totalSafe).coerceIn(0, 100),
                        todayDownloadPercent = todayPercent,
                        todayDownloadGb = effectiveTodayMb / 1024.0,
                        totalDownloadGb = effectiveTotalMb / 1024.0,
                        topPackageSubtitle = topPkgSubtitle,
                        dataSourceNote = sourceNote,
                        isConnectedToMikrotik = connected
                    )
                }
            }
        }
    }

    fun refreshFromMikrotik() {
        val session = LocalSessionHolder.activeSession.value
        if (session == null || session.hostAddress.isBlank() || !session.isConnectedToMikrotik) {
            return
        }
        if (_uiState.value.isRefreshingMikrotik) return

        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshingMikrotik = true, errorMessage = null) }
            val allCards = repository.getAllCardsOnce()
            val result = MikrotikSyncManager.fetchLiveReportsMetrics(
                credentials = session.toCredentials(),
                loadType = session.loadType,
                cards = allCards,
                packages = latestPackages,
                repository = repository
            )
            result.onSuccess { metrics ->
                LocalSessionHolder.updateLiveReportsMetrics(metrics)
                _uiState.update {
                    it.copy(
                        isRefreshingMikrotik = false,
                        statusMessage = "تم تحديث التقارير والمبيعات الحية من يوزر منجر."
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isRefreshingMikrotik = false,
                        errorMessage = err.localizedMessage ?: "تعذر جلب تفاصيل الجلسات والمدفوعات من المايكروتيك."
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

    private data class ReportsBundle(
        val localReport: ComprehensiveSalesReport,
        val cardCounts: CardCountsSummary,
        val logs: List<PrintSalesLogEntity>,
        val packages: List<CardPackageEntity>,
        val session: ActiveSession?,
        val liveMetrics: MikrotikLiveReportsMetrics?
    )
}
