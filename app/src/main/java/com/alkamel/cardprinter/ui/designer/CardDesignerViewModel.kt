package com.alkamel.cardprinter.ui.designer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alkamel.cardprinter.data.local.entity.CardEntity
import com.alkamel.cardprinter.data.local.entity.CardPackageEntity
import com.alkamel.cardprinter.data.local.entity.CardTemplateEntity
import com.alkamel.cardprinter.data.printing.CardBitmapRenderer
import com.alkamel.cardprinter.data.repository.CardPrinterRepository
import com.alkamel.cardprinter.ui.components.DesignerElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.floor
import kotlin.math.roundToInt

data class CardDesignerUiState(
    val templates: List<CardTemplateEntity> = emptyList(),
    val packages: List<CardPackageEntity> = emptyList(),
    val activeTemplate: CardTemplateEntity = CardTemplateEntity(name = "قالب الكامل A4"),
    val widthInput: String = "203",
    val heightInput: String = "61",
    val columnsInput: String = "4",
    val rowsInput: String = "25",
    val cardsPerA4Page: Int = 100,
    val showA4PreviewDialog: Boolean = false,
    val a4PreviewBitmap: Bitmap? = null,
    val isRenderingA4Preview: Boolean = false,
    val selectedElement: DesignerElement = DesignerElement.USERNAME,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

class CardDesignerViewModel(
    private val repository: CardPrinterRepository
) : ViewModel() {

    companion object {
        // Printable A4 reference area calibrated so default 203x61 fits 4 cols x 25 rows = 100 cards
        private const val A4_PRINTABLE_REF_W = 832f
        private const val A4_PRINTABLE_REF_H = 1625f
        private const val A4_CARD_GAP = 4f
    }

    private val _uiState = MutableStateFlow(CardDesignerUiState())
    val uiState: StateFlow<CardDesignerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                repository.observeAllTemplates(),
                repository.observeAllPackages()
            ) { templates, packages ->
                templates to packages
            }.collect { (templates, packages) ->
                _uiState.update { current ->
                    val shouldLoadDefault = current.templates.isEmpty() && templates.isNotEmpty()
                    if (shouldLoadDefault) {
                        val def = templates.firstOrNull { it.isDefault } ?: templates.first()
                        current.copy(
                            templates = templates,
                            packages = packages,
                            activeTemplate = def,
                            widthInput = def.cardWidth.toInt().toString(),
                            heightInput = def.cardHeight.toInt().toString(),
                            columnsInput = def.columnsPerPage.toString(),
                            rowsInput = def.rowsPerPage.toString(),
                            cardsPerA4Page = def.columnsPerPage * def.rowsPerPage
                        )
                    } else {
                        current.copy(templates = templates, packages = packages)
                    }
                }
            }
        }
    }

    fun selectTemplate(template: CardTemplateEntity) {
        _uiState.update {
            it.copy(
                activeTemplate = template,
                widthInput = template.cardWidth.toInt().toString(),
                heightInput = template.cardHeight.toInt().toString(),
                columnsInput = template.columnsPerPage.toString(),
                rowsInput = template.rowsPerPage.toString(),
                cardsPerA4Page = template.columnsPerPage * template.rowsPerPage
            )
        }
    }

    fun createNewBlankTemplate() {
        val fresh = CardTemplateEntity(
            id = 0L,
            name = "قالب يوزر منجر ${_uiState.value.templates.size + 1}",
            packageName = "الكل",
            cardWidth = 203f,
            cardHeight = 61f,
            columnsPerPage = 4,
            rowsPerPage = 25
        )
        selectTemplate(fresh)
    }

    fun onTemplateNameChange(value: String) = updateTemplate { it.copy(name = value) }
    fun onPackageNameChange(value: String) = updateTemplate { it.copy(packageName = value) }
    fun onSelectedElementChange(element: DesignerElement) = _uiState.update { it.copy(selectedElement = element) }
    fun clearMessages() = _uiState.update { it.copy(statusMessage = null, errorMessage = null) }

    /**
     * Automatically recalculates fitting columns & total cards per A4 page immediately
     * whenever the user changes card width.
     */
    fun onWidthChange(value: String) {
        val filtered = value.filter { it.isDigit() }.take(4)
        val parsed = filtered.toFloatOrNull()?.coerceIn(60f, 500f)
        _uiState.update { current ->
            if (parsed == null) {
                current.copy(widthInput = filtered)
            } else {
                val autoCols = calculateFittingColumns(parsed)
                val rows = current.activeTemplate.rowsPerPage.coerceIn(1, 30)
                val updatedTpl = current.activeTemplate.copy(
                    cardWidth = parsed,
                    columnsPerPage = autoCols
                )
                current.copy(
                    widthInput = filtered,
                    columnsInput = autoCols.toString(),
                    activeTemplate = updatedTpl,
                    cardsPerA4Page = autoCols * rows
                )
            }
        }
    }

    /**
     * Automatically recalculates fitting rows & total cards per A4 page immediately
     * whenever the user changes card height.
     */
    fun onHeightChange(value: String) {
        val filtered = value.filter { it.isDigit() }.take(4)
        val parsed = filtered.toFloatOrNull()?.coerceIn(30f, 300f)
        _uiState.update { current ->
            if (parsed == null) {
                current.copy(heightInput = filtered)
            } else {
                val cols = current.activeTemplate.columnsPerPage.coerceIn(1, 6)
                val autoRows = calculateFittingRows(parsed)
                val updatedTpl = current.activeTemplate.copy(
                    cardHeight = parsed,
                    rowsPerPage = autoRows
                )
                current.copy(
                    heightInput = filtered,
                    rowsInput = autoRows.toString(),
                    activeTemplate = updatedTpl,
                    cardsPerA4Page = cols * autoRows
                )
            }
        }
    }

    fun onColumnsChange(value: String) {
        val filtered = value.filter { it.isDigit() }.take(1)
        val parsed = filtered.toIntOrNull()?.coerceIn(1, 6)
        _uiState.update { current ->
            if (parsed == null) {
                current.copy(columnsInput = filtered)
            } else {
                val updatedTpl = current.activeTemplate.copy(columnsPerPage = parsed)
                current.copy(
                    columnsInput = filtered,
                    activeTemplate = updatedTpl,
                    cardsPerA4Page = parsed * updatedTpl.rowsPerPage
                )
            }
        }
    }

    fun onRowsChange(value: String) {
        val filtered = value.filter { it.isDigit() }.take(2)
        val parsed = filtered.toIntOrNull()?.coerceIn(1, 30)
        _uiState.update { current ->
            if (parsed == null) {
                current.copy(rowsInput = filtered)
            } else {
                val updatedTpl = current.activeTemplate.copy(rowsPerPage = parsed)
                current.copy(
                    rowsInput = filtered,
                    activeTemplate = updatedTpl,
                    cardsPerA4Page = updatedTpl.columnsPerPage * parsed
                )
            }
        }
    }

    fun openFullA4Preview() {
        val tpl = _uiState.value.activeTemplate
        _uiState.update { it.copy(showA4PreviewDialog = true, isRenderingA4Preview = true) }
        viewModelScope.launch {
            val rendered = withContext(Dispatchers.Default) {
                renderFullA4PageBitmap(tpl)
            }
            _uiState.update {
                it.copy(
                    a4PreviewBitmap = rendered,
                    isRenderingA4Preview = false
                )
            }
        }
    }

    fun closeFullA4Preview() {
        _uiState.update { it.copy(showA4PreviewDialog = false) }
    }

    fun onToggleShowUsername(show: Boolean) = updateTemplate { it.copy(showUsername = show) }
    fun onToggleShowPassword(show: Boolean) = updateTemplate { it.copy(showPassword = show) }
    fun onToggleShowQrCode(show: Boolean) = updateTemplate { it.copy(showQrCode = show) }
    fun onTextColorSelected(hex: String) = updateTemplate { it.copy(textColorHex = hex) }
    fun onFontSizeChange(sizeSp: Float) = updateTemplate { it.copy(fontSizeSp = sizeSp.coerceIn(10f, 28f)) }
    fun clearBackgroundImage() = updateTemplate { it.copy(backgroundImagePath = null) }

    fun onElementDragged(element: DesignerElement, deltaRelX: Float, deltaRelY: Float) {
        _uiState.update { state ->
            val t = state.activeTemplate
            val updated = when (element) {
                DesignerElement.USERNAME -> t.copy(
                    usernameX = (t.usernameX + deltaRelX).coerceIn(0.05f, 0.95f),
                    usernameY = (t.usernameY + deltaRelY).coerceIn(0.10f, 0.90f)
                )
                DesignerElement.PASSWORD -> t.copy(
                    passwordX = (t.passwordX + deltaRelX).coerceIn(0.05f, 0.95f),
                    passwordY = (t.passwordY + deltaRelY).coerceIn(0.10f, 0.90f)
                )
                DesignerElement.QR_CODE -> t.copy(
                    qrX = (t.qrX + deltaRelX).coerceIn(0.08f, 0.92f),
                    qrY = (t.qrY + deltaRelY).coerceIn(0.12f, 0.88f)
                )
            }
            state.copy(selectedElement = element, activeTemplate = updated)
        }
    }

    fun copyPickedBackgroundImage(context: Context, uri: Uri) {
        viewModelScope.launch {
            val savedPath = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.filesDir, "card_templates").apply { mkdirs() }
                    val file = File(dir, "template_bg_${System.currentTimeMillis()}.jpg")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(file).use { output -> input.copyTo(output) }
                    }
                    file.absolutePath
                }.getOrNull()
            }
            if (savedPath != null) {
                updateTemplate { it.copy(backgroundImagePath = savedPath) }
                _uiState.update { it.copy(statusMessage = "تم تعيين صورة خلفية القالب بنجاح.") }
            } else {
                _uiState.update { it.copy(errorMessage = "تعذر قراءة الصورة المختارة.") }
            }
        }
    }

    fun saveActiveTemplate() {
        val current = _uiState.value.activeTemplate
        if (current.name.isBlank()) {
            _uiState.update { it.copy(errorMessage = "يرجى إدخال اسم القالب أولاً.") }
            return
        }
        viewModelScope.launch {
            val toSave = current.copy(
                name = current.name.trim(),
                updatedAt = System.currentTimeMillis()
            )
            repository.saveTemplate(toSave)
                .onSuccess { newId ->
                    _uiState.update {
                        it.copy(
                            activeTemplate = toSave.copy(id = if (toSave.id == 0L) newId else toSave.id),
                            statusMessage = "تم حفظ قالب (${toSave.name}) في قاعدة البيانات."
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(errorMessage = err.localizedMessage ?: "تعذر حفظ القالب") }
                }
        }
    }

    fun deleteActiveTemplate() {
        val current = _uiState.value.activeTemplate
        if (current.id == 0L) {
            createNewBlankTemplate()
            return
        }
        viewModelScope.launch {
            repository.deleteTemplate(current)
                .onSuccess {
                    _uiState.update { it.copy(statusMessage = "تم حذف القالب (${current.name}).") }
                    createNewBlankTemplate()
                }
                .onFailure { err ->
                    _uiState.update { it.copy(errorMessage = err.localizedMessage ?: "تعذر حذف القالب") }
                }
        }
    }

    private fun calculateFittingColumns(cardWidth: Float): Int {
        return floor((A4_PRINTABLE_REF_W + A4_CARD_GAP) / (cardWidth + A4_CARD_GAP))
            .toInt()
            .coerceIn(1, 6)
    }

    private fun calculateFittingRows(cardHeight: Float): Int {
        return floor((A4_PRINTABLE_REF_H + A4_CARD_GAP) / (cardHeight + A4_CARD_GAP))
            .toInt()
            .coerceIn(1, 30)
    }

    private fun renderFullA4PageBitmap(template: CardTemplateEntity): Bitmap {
        val pageW = 900
        val pageH = 1273 // ISO A4 ratio 210:297
        val margin = 24f
        val headerH = 38f
        val gap = 3f

        val cols = template.columnsPerPage.coerceIn(1, 6)
        val rows = template.rowsPerPage.coerceIn(1, 30)
        val perPage = cols * rows

        val cellW = (pageW - margin * 2 - gap * (cols - 1)) / cols
        val ratio = template.cardHeight.coerceAtLeast(20f) / template.cardWidth.coerceAtLeast(40f)
        val maxRowH = (pageH - margin * 2 - headerH - gap * (rows - 1)) / rows
        val cellH = minOf(cellW * ratio, maxRowH)

        val pageBmp = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(pageBmp)
        canvas.drawColor(Color.WHITE)

        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 17f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#475569")
            textSize = 14f
            textAlign = Paint.Align.LEFT
        }
        val cutPaint = Paint().apply {
            color = Color.parseColor("#94A3B8")
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }

        canvas.drawText("${template.name} • يوزر منجر ($perPage كرت/صفحة)", pageW - margin, margin + 18f, headerPaint)
        canvas.drawText("A4 • $cols×$rows", margin, margin + 18f, metaPaint)

        val sampleCard = CardEntity(
            username = "15650109767",
            password = "4821",
            packageName = template.packageName
        )
        val cardBmp = CardBitmapRenderer.renderCardBitmap(
            card = sampleCard,
            template = template,
            widthPx = cellW.roundToInt().coerceAtLeast(140),
            heightPx = cellH.roundToInt().coerceAtLeast(42)
        )
        val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val colRtl = (cols - 1) - c
                val left = margin + colRtl * (cellW + gap)
                val top = margin + headerH + r * (cellH + gap)
                val dest = RectF(left, top, left + cellW, top + cellH)
                canvas.drawBitmap(cardBmp, null, dest, bmpPaint)
                canvas.drawRect(dest, cutPaint)
            }
        }
        cardBmp.recycle()
        return pageBmp
    }

    private fun updateTemplate(transform: (CardTemplateEntity) -> CardTemplateEntity) {
        _uiState.update {
            val next = transform(it.activeTemplate)
            it.copy(
                activeTemplate = next,
                cardsPerA4Page = next.columnsPerPage * next.rowsPerPage
            )
        }
    }
}
