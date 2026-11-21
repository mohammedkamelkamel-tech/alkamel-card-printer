package com.alkamel.cardprinter.ui.reports

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alkamel.cardprinter.data.local.entity.CardEntity
import com.alkamel.cardprinter.data.local.entity.CardPackageEntity
import com.alkamel.cardprinter.data.local.entity.PrintSalesLogEntity
import com.alkamel.cardprinter.data.local.model.CardStatus
import com.alkamel.cardprinter.data.local.model.ComprehensiveSalesReport
import com.alkamel.cardprinter.data.local.model.PeriodSalesSummary
import com.alkamel.cardprinter.data.local.model.TopSellingPackageStat
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

    private val liveMetricsFlow = MutableStateFlow<MikrotikLiveReportsMetrics?>(null)
    private var latestCards: List<CardEntity> = emptyList()
    private var latestPackages: List<CardPackageEntity> = emptyList()

    init {
        viewModelScope.launch {
            val localDataFlow = combine(
                repository.observeComprehensiveSalesReport(),
                repository.observeAllCards(),
                repository.observeAllSalesLogs()
            ) { report, cards, logs ->
                Triple(report, cards, logs)
            }
            val metaDataFlow = combine(
                repository.observeAllPackages(),
                LocalSessionHolder.activeSession,
                liveMetricsFlow
            ) { packages, session, liveMetrics ->
                Triple(packages, session, liveMetrics)
            }
            combine(localDataFlow, metaDataFlow) { localTriple, metaTriple ->
                ReportsBundle(
                    localReport = localTriple.first,
                    cards = localTriple.second,
                    logs = localTriple.third,
                    packages = metaTriple.first,
                    session = metaTriple.second,
                    liveMetrics = metaTriple.third
                )
            }.collect { bundle ->
                latestCards = bundle.cards
                latestPackages = bundle.packages

                val cards = bundle.cards
                val logs = bundle.logs
                val localReport = bundle.localReport
                val live = bundle.liveMetrics
                val connected = bundle.session?.isConnectedToMikrotik == true

                val total = cards.size.coerceAtLeast(1)
                val expired = cards.count { it.status == CardStatus.EXPIRED }
                val used = cards.count { it.status == CardStatus.USED }
                val unused = cards.count { it.status == CardStatus.UNUSED }

                // Today download: combine local printed logs + live MikroTik sessions / active download
                val todayKey = DateTimeUtils.todayDateKey()
                val localTodayMb = logs.filter { it.dateKey == todayKey }.sumOf { it.downloadMb }
                val liveTodayBytes = live?.todayDownloadBytes
                    ?: bundle.session?.systemStatus?.totalDownloadBytes
                    ?: 0L
                val liveTodayMb = liveTodayBytes / (1024.0 * 1024.0)
                val effectiveTodayMb = maxOf(localTodayMb, liveTodayMb)

                val cardsTotalMb = cards.sumOf { it.dataUsageMb }
                val logsTotalMb = logs.sumOf { it.downloadMb }
                val effectiveTotalMb = maxOf(cardsTotalMb + logsTotalMb, effectiveTodayMb)

                val todayPercent = when {
                    effectiveTotalMb > 0.0 && effectiveTodayMb > 0.0 -> {
                        val raw = ((effectiveTodayMb / effectiveTotalMb) * 100.0).toInt()
                        if (raw == 0) 1 else raw.coerceIn(1, 100)
                    }
                    else -> 0
                }

                // Merge period sales: prefer higher real activity between local print logs and live MikroTik sessions/payments
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

                // Top selling / most consumed package:
                // Use local sales log top package if available; otherwise compute from real USED + EXPIRED MikroTik cards
                val priceByPkg = bundle.packages.associate { it.name.lowercase() to it.price }
                val derivedTopPkg = localReport.topSellingPackage ?: cards
                    .filter { it.status != CardStatus.UNUSED && it.packageName.isNotBlank() }
                    .groupBy { it.packageName }
                    .map { (pkgName, pkgCards) ->
                        val unitPrice = priceByPkg[pkgName.lowercase()]
                            ?.takeIf { it > 0.0 }
                            ?: pkgCards.firstOrNull { it.price > 0.0 }?.price
                            ?: MikrotikSyncManager.inferPriceFromPackageName(pkgName)
                        TopSellingPackageStat(
                            packageName = pkgName,
                            totalCards = pkgCards.size,
                            totalAmount = pkgCards.size * unitPrice
                        )
                    }
                    .maxByOrNull { it.totalCards }

                val topPkgSubtitle = if (derivedTopPkg != null) {
                    "${derivedTopPkg.totalCards} كرت مستهلك • متبقي: $unused"
                } else {
                    "متبقي غير مستخدم: $unused"
                }

                val mergedReport = ComprehensiveSalesReport(
                    today = mergedToday,
                    yesterday = mergedYesterday,
                    weekly = mergedWeekly,
                    monthly = mergedMonthly,
                    allTime = if (localReport.allTime.totalCards > 0) localReport.allTime else mergedMonthly,
                    topSellingPackage = derivedTopPkg
                )

                val sourceNote = when {
                    live != null && live.sourceNote.isNotBlank() -> live.sourceNote
                    connected -> "بيانات حية من المايكروتيك (${cards.size} كرت متزامن)"
                    cards.isNotEmpty() -> "بيانات من قاعدة البيانات المحلية (${cards.size} كرت محفوظ)"
                    else -> "لا توجد بيانات بعد — اتصل بالمايكروتيك وقم بالمزامنة"
                }

                _uiState.update {
                    it.copy(
                        report = mergedReport,
                        allLogs = logs,
                        totalCards = cards.size,
                        expiredCardsCount = expired,
                        usedCardsCount = used,
                        unusedCardsCount = unused,
                        expiredPercent = if (cards.isEmpty()) 0 else ((expired * 100) / total).coerceIn(0, 100),
                        usedPercent = if (cards.isEmpty()) 0 else ((used * 100) / total).coerceIn(0, 100),
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
            val result = MikrotikSyncManager.fetchLiveReportsMetrics(
                credentials = session.toCredentials(),
                loadType = session.loadType,
                cards = latestCards,
                packages = latestPackages
            )
            result.onSuccess { metrics ->
                liveMetricsFlow.value = metrics
                _uiState.update {
                    it.copy(
                        isRefreshingMikrotik = false,
                        statusMessage = "تم تحديث التقارير الحية من المايكروتيك."
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
        val cards: List<CardEntity>,
        val logs: List<PrintSalesLogEntity>,
        val packages: List<CardPackageEntity>,
        val session: ActiveSession?,
        val liveMetrics: MikrotikLiveReportsMetrics?
    )
}
