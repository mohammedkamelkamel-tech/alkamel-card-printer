package com.alkamel.cardprinter.ui.printpreview

import android.content.Context
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
import com.alkamel.cardprinter.data.printing.CardLayoutMetrics
import com.alkamel.cardprinter.data.printing.PdfPrintEngine
import com.alkamel.cardprinter.data.printing.PdfResult
import com.alkamel.cardprinter.data.repository.CardPrinterRepository
import com.alkamel.cardprinter.data.session.LocalSessionHolder
import com.alkamel.cardprinter.data.session.PrintQueueHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.ceil

data class PrintPreviewUiState(
    val cardsToPrint: List<CardEntity> = emptyList(),
    val templates: List<CardTemplateEntity> = emptyList(),
    val activeTemplate: CardTemplateEntity = CardLayoutMetrics.sanitize(CardTemplateEntity(name = "قالب الكامل A4")),
    val settings: AppSettingsState = AppSettingsState(),
    val currentPageIndex: Int = 0,
    val totalPages: Int = 1,
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

                _uiState.update { current ->
                    // Always resolve the freshest saved version from Room so edits (coordinates, image, font, color)
                    // are immediately reflected in both preview and PDF export.
                    val manualSelection = userManuallyPickedTemplateId?.let { id ->
                        snap.templates.firstOrNull { it.id == id }
                    }
                    val freshQueued = snap.queuedTemplate?.let { qt ->
                        snap.templates.firstOrNull { it.id == qt.id } ?: qt
                    }
                    val matchedByPackage = CardLayoutMetrics.selectBestTemplateForPackage(
                        templates = snap.templates,
                        packageName = batchPackageName
                    )
                    val chosenTemplate = CardLayoutMetrics.sanitize(
                        manualSelection
                            ?: freshQueued
                            ?: matchedByPackage
                            ?: current.activeTemplate
                    )

                    val perPage = (chosenTemplate.columnsPerPage.coerceIn(1, 6) * chosenTemplate.rowsPerPage.coerceIn(1, 30)).coerceAtLeast(1)
                    val pages = ceil(maxOf(1, effectiveCards.size).toDouble() / perPage).toInt()
                    current.copy(
                        cardsToPrint = effectiveCards,
                        templates = snap.templates,
                        activeTemplate = chosenTemplate,
                        settings = snap.settings,
                        totalPages = pages,
                        currentPageIndex = current.currentPageIndex.coerceIn(0, (pages - 1).coerceAtLeast(0))
                    )
                }
            }
        }
    }

    fun selectTemplate(template: CardTemplateEntity) {
        val safe = CardLayoutMetrics.sanitize(template)
        userManuallyPickedTemplateId = safe.id
        _uiState.update { current ->
            val perPage = (safe.columnsPerPage.coerceIn(1, 6) * safe.rowsPerPage.coerceIn(1, 30)).coerceAtLeast(1)
            val pages = ceil(maxOf(1, current.cardsToPrint.size).toDouble() / perPage).toInt()
            current.copy(
                activeTemplate = safe,
                totalPages = pages,
                currentPageIndex = 0
            )
        }
    }

    fun nextPage() {
        _uiState.update {
            if (it.currentPageIndex + 1 < it.totalPages) it.copy(currentPageIndex = it.currentPageIndex + 1) else it
        }
    }

    fun previousPage() {
        _uiState.update {
            if (it.currentPageIndex > 0) it.copy(currentPageIndex = it.currentPageIndex - 1) else it
        }
    }

    fun clearMessages() = _uiState.update { it.copy(statusMessage = null, errorMessage = null) }

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
