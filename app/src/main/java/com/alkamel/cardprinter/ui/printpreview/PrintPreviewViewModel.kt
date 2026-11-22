package com.alkamel.cardprinter.ui.printpreview

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alkamel.cardprinter.data.local.entity.CardEntity
import com.alkamel.cardprinter.data.local.entity.CardTemplateEntity
import com.alkamel.cardprinter.data.local.entity.PrintSalesLogEntity
import com.alkamel.cardprinter.data.local.model.PrintMethod
import com.alkamel.cardprinter.data.local.model.SaleOperationType
import com.alkamel.cardprinter.data.local.util.DateTimeUtils
import com.alkamel.cardprinter.data.preferences.AppPreferencesManager
import com.alkamel.cardprinter.data.preferences.AppSettingsState
import com.alkamel.cardprinter.data.printing.CardBitmapRenderer
import com.alkamel.cardprinter.data.printing.CardLayoutMetrics
import com.alkamel.cardprinter.data.printing.PdfPrintEngine
import com.alkamel.cardprinter.data.printing.PdfResult
import com.alkamel.cardprinter.data.repository.CardPrinterRepository
import com.alkamel.cardprinter.data.session.LocalSessionHolder
import com.alkamel.cardprinter.data.session.PrintQueueHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

data class PrintPreviewUiState(
    val cardsToPrint: List<CardEntity> = emptyList(),
    val templates: List<CardTemplateEntity> = emptyList(),
    val activeTemplate: CardTemplateEntity = CardLayoutMetrics.sanitize(CardTemplateEntity(name = "قالب الكامل A4")),
    val settings: AppSettingsState = AppSettingsState(),
    val currentPageIndex: Int = 0,
    val totalPages: Int = 1,
    val previewPageBitmap: Bitmap? = null,
    val isRenderingPreview: Boolean = false,
    val isGeneratingPdf: Boolean = false,
    val lastGeneratedPdfFile: File? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

class PrintPreviewViewModel(
    private val repository: CardPrinterRepository,
    private val preferencesManager: AppPreferencesManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(PrintPreviewUiState())
    val uiState: StateFlow<PrintPreviewUiState> = _uiState.asStateFlow()

    private var userManuallyPickedTemplateId: Long? = null
    private var previewRenderJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                PrintQueueHolder.queue,
                repository.observeAllTemplates(),
                repository.observeAllCards(),
                preferencesManager.settingsFlow
            ) { queue, templates, allCards, settings ->
                QueueSnapshot(queue.cards, queue.template, templates, allCards, settings)
            }.collect { snap ->
                val effectiveCards = if (snap.queuedCards.isNotEmpty()) {
                    snap.queuedCards
                } else {
                    snap.allCards.filter { !it.isPrinted }.ifEmpty { snap.allCards.take(100) }
                }
                val batchPackageName = effectiveCards.firstOrNull()?.packageName

                var nextState: PrintPreviewUiState? = null
                _uiState.update { current ->
                    val manualSelection = userManuallyPickedTemplateId?.let { id ->
                        snap.templates.firstOrNull { it.id == id }
                    }
                    val matchedByPackage = CardLayoutMetrics.selectBestTemplateForPackage(
                        templates = snap.templates,
                        packageName = batchPackageName
                    )
                    val freshQueued = snap.queuedTemplate?.let { qt ->
                        snap.templates.firstOrNull { it.id == qt.id } ?: qt
                    }
                    val chosenTemplate = CardLayoutMetrics.sanitize(
                        manualSelection
                            ?: matchedByPackage
                            ?: freshQueued
                            ?: current.activeTemplate
                    )

                    val perPage = (chosenTemplate.columnsPerPage.coerceIn(1, 6) * chosenTemplate.rowsPerPage.coerceIn(1, 30)).coerceAtLeast(1)
                    val pages = ceil(maxOf(1, effectiveCards.size).toDouble() / perPage).toInt()
                    val clampedPage = current.currentPageIndex.coerceIn(0, (pages - 1).coerceAtLeast(0))
                    val updated = current.copy(
                        cardsToPrint = effectiveCards,
                        templates = snap.templates,
                        activeTemplate = chosenTemplate,
                        settings = snap.settings,
                        totalPages = pages,
                        currentPageIndex = clampedPage
                    )
                    nextState = updated
                    updated
                }
                nextState?.let { scheduleA4PreviewRender(it) }
            }
        }
    }

    fun selectTemplate(template: CardTemplateEntity) {
        val safe = CardLayoutMetrics.sanitize(template)
        userManuallyPickedTemplateId = safe.id
        var nextState: PrintPreviewUiState? = null
        _uiState.update { current ->
            val perPage = (safe.columnsPerPage.coerceIn(1, 6) * safe.rowsPerPage.coerceIn(1, 30)).coerceAtLeast(1)
            val pages = ceil(maxOf(1, current.cardsToPrint.size).toDouble() / perPage).toInt()
            val updated = current.copy(
                activeTemplate = safe,
                totalPages = pages,
                currentPageIndex = 0
            )
            nextState = updated
            updated
        }
        nextState?.let { scheduleA4PreviewRender(it) }
    }

    fun nextPage() {
        var nextState: PrintPreviewUiState? = null
        _uiState.update {
            if (it.currentPageIndex + 1 < it.totalPages) {
                val updated = it.copy(currentPageIndex = it.currentPageIndex + 1)
                nextState = updated
                updated
            } else it
        }
        nextState?.let { scheduleA4PreviewRender(it) }
    }

    fun previousPage() {
        var nextState: PrintPreviewUiState? = null
        _uiState.update {
            if (it.currentPageIndex > 0) {
                val updated = it.copy(currentPageIndex = it.currentPageIndex - 1)
                nextState = updated
                updated
            } else it
        }
        nextState?.let { scheduleA4PreviewRender(it) }
    }

    fun clearMessages() = _uiState.update { it.copy(statusMessage = null, errorMessage = null) }

    /**
     * Renders the exact ISO A4 page preview using the identical CardBitmapRenderer.renderA4PageBitmap
     * engine used by PdfPrintEngine so on-screen preview and exported PDF match 100%.
     */
    private fun scheduleA4PreviewRender(state: PrintPreviewUiState) {
        previewRenderJob?.cancel()
        if (state.cardsToPrint.isEmpty()) {
            _uiState.update { it.copy(previewPageBitmap = null, isRenderingPreview = false) }
            return
        }
        previewRenderJob = viewModelScope.launch {
            _uiState.update { it.copy(isRenderingPreview = true) }
            val rendered = withContext(Dispatchers.Default) {
                val tpl = CardLayoutMetrics.sanitize(state.activeTemplate)
                val cols = tpl.columnsPerPage.coerceIn(1, 6)
                val rows = tpl.rowsPerPage.coerceIn(1, 30)
                val perPage = (cols * rows).coerceAtLeast(1)
                val startIdx = state.currentPageIndex * perPage
                val slice = if (startIdx >= state.cardsToPrint.size) {
                    emptyList()
                } else {
                    state.cardsToPrint.subList(startIdx, minOf(startIdx + perPage, state.cardsToPrint.size))
                }
                val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date())
                val pkgSuffix = if (tpl.packageName == "الكل") "" else " • ${tpl.packageName}"
                CardBitmapRenderer.renderA4PageBitmap(
                    cardsOnPage = slice,
                    template = tpl,
                    headerTitle = "الكامل-طباعة الكروت • ${tpl.name}$pkgSuffix (${state.cardsToPrint.size} كرت)",
                    headerMeta = "A4 • صفحة ${state.currentPageIndex + 1}/${state.totalPages}  |  $dateStr",
                    showCutGuides = state.settings.showCutGuidesOnA4,
                    showPageHeader = state.settings.showHeaderOnA4
                )
            }
            _uiState.update { it.copy(previewPageBitmap = rendered, isRenderingPreview = false) }
        }
    }

    fun generateAndPrintA4(
        context: Context,
        launchDirectPrint: Boolean,
        onPdfReady: (File) -> Unit
    ) {
        val state = _uiState.value
        if (state.cardsToPrint.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "لا توجد كروت جاهزة للطباعة.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isGeneratingPdf = true, errorMessage = null) }
            val result = PdfPrintEngine.generateCardsGridPdf(
                context = context,
                cards = state.cardsToPrint,
                template = CardLayoutMetrics.sanitize(state.activeTemplate),
                title = "الكامل-طباعة الكروت",
                showCutGuides = state.settings.showCutGuidesOnA4,
                showPageHeader = state.settings.showHeaderOnA4
            )
            when (result) {
                is PdfResult.Success -> {
                    markPrintedAndLogSale(
                        cards = state.cardsToPrint,
                        method = if (launchDirectPrint) PrintMethod.ANDROID_PRINT_A4 else PrintMethod.PDF_A4_GRID
                    )
                    _uiState.update {
                        it.copy(
                            isGeneratingPdf = false,
                            lastGeneratedPdfFile = result.file,
                            statusMessage = "تم تجهيز ملف الطباعة A4 لـ ${state.cardsToPrint.size} كرت بنجاح."
                        )
                    }
                    onPdfReady(result.file)
                }
                is PdfResult.Error -> {
                    _uiState.update { it.copy(isGeneratingPdf = false, errorMessage = result.message) }
                }
            }
        }
    }

    private suspend fun markPrintedAndLogSale(cards: List<CardEntity>, method: PrintMethod) {
        val now = System.currentTimeMillis()
        val dateKey = DateTimeUtils.todayDateKey()
        val persistedIds = cards.map { it.id }.filter { it > 0L }
        if (persistedIds.isNotEmpty()) {
            repository.markCardsAsPrinted(persistedIds, now, dateKey)
        }
        val first = cards.first()
        val totalAmount = cards.sumOf { it.price }
        val totalCommission = cards.sumOf { it.commission }
        val account = first.serverAccount.ifBlank { LocalSessionHolder.activeSession.value?.username.orEmpty() }
        repository.recordSalesLog(
            PrintSalesLogEntity(
                cardUsername = if (cards.size == 1) first.username else "دفعة ${cards.size} كرت (${first.username})",
                packageName = first.packageName,
                cardsCount = cards.size,
                unitPrice = first.price,
                totalAmount = totalAmount,
                commissionTotal = totalCommission,
                serverAccount = account,
                downloadLabel = "0 MB",
                downloadMb = 0.0,
                printMethod = method,
                operationType = SaleOperationType.PRINT_SALE,
                dateKey = dateKey,
                createdAt = now
            )
        )
    }

    private data class QueueSnapshot(
        val queuedCards: List<CardEntity>,
        val queuedTemplate: CardTemplateEntity?,
        val templates: List<CardTemplateEntity>,
        val allCards: List<CardEntity>,
        val settings: AppSettingsState
    )
}
