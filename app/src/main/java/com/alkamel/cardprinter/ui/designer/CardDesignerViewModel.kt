package com.alkamel.cardprinter.ui.designer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.alkamel.cardprinter.data.printing.CardLayoutMetrics
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
import java.util.UUID
import kotlin.math.roundToInt

data class CardDesignerUiState(
    val templates: List<CardTemplateEntity> = emptyList(),
    val packages: List<CardPackageEntity> = emptyList(),
    val activeTemplate: CardTemplateEntity = CardLayoutMetrics.sanitize(CardTemplateEntity(name = "قالب الكامل A4")),
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

    private val _uiState = MutableStateFlow(CardDesignerUiState())
    val uiState: StateFlow<CardDesignerUiState> = _uiState.asStateFlow()

    private var hasInitializedFromDb = false

    init {
        viewModelScope.launch {
            combine(
                repository.observeAllTemplates(),
                repository.observeAllPackages()
            ) { templates, packages ->
                templates to packages
            }.collect { (templates, packages) ->
                _uiState.update { current ->
                    if (!hasInitializedFromDb && templates.isNotEmpty()) {
                        hasInitializedFromDb = true
                        // Load the most recently saved template so edits persist across sessions
                        val initial = CardLayoutMetrics.sanitize(templates.maxByOrNull { it.updatedAt } ?: templates.first())
                        current.copy(
                            templates = templates,
                            packages = packages,
                            activeTemplate = initial,
                            widthInput = initial.cardWidth.toInt().toString(),
                            heightInput = initial.cardHeight.toInt().toString(),
                            columnsInput = initial.columnsPerPage.toString(),
                            rowsInput = initial.rowsPerPage.toString(),
                            cardsPerA4Page = initial.columnsPerPage * initial.rowsPerPage
                        )
                    } else {
                        current.copy(templates = templates, packages = packages)
                    }
                }
            }
        }
    }

    fun selectTemplate(template: CardTemplateEntity) {
        val safe = CardLayoutMetrics.sanitize(template)
        _uiState.update {
            it.copy(
                activeTemplate = safe,
                widthInput = safe.cardWidth.toInt().toString(),
                heightInput = safe.cardHeight.toInt().toString(),
                columnsInput = safe.columnsPerPage.toString(),
                rowsInput = safe.rowsPerPage.toString(),
                cardsPerA4Page = safe.columnsPerPage * safe.rowsPerPage
            )
        }
    }

    fun createNewBlankTemplate() {
        val fresh = CardLayoutMetrics.sanitize(
            CardTemplateEntity(
                id = 0L,
                name = "قالب يوزر منجر ${_uiState.value.templates.size + 1}",
                packageName = "الكل",
                backgroundImagePath = null,
                cardWidth = 203f,
                cardHeight = 61f,
                columnsPerPage = 4,
                rowsPerPage = 25,
                showUsername = true,
                showPassword = false,
                showQrCode = false,
                usernameX = CardLayoutMetrics.DEFAULT_USERNAME_X,
                usernameY = CardLayoutMetrics.DEFAULT_USERNAME_Y,
                passwordX = CardLayoutMetrics.DEFAULT_PASSWORD_X,
                passwordY = CardLayoutMetrics.DEFAULT_PASSWORD_Y,
                qrX = CardLayoutMetrics.DEFAULT_QR_X,
                qrY = CardLayoutMetrics.DEFAULT_QR_Y,
                textColorHex = "#FFFFFF",
                fontSizeSp = 16f,
                isDefault = false
            )
        )
        selectTemplate(fresh)
    }

    fun onTemplateNameChange(value: String) = updateTemplate { it.copy(name = value) }

    fun onPackageNameChange(value: String) {
        val normalized = value.trim().ifBlank { "الكل" }
        updateTemplate { it.copy(packageName = normalized) }
    }

    fun onSelectedElementChange(element: DesignerElement) = _uiState.update { it.copy(selectedElement = element) }
    fun clearMessages() = _uiState.update { it.copy(statusMessage = null, errorMessage = null) }

    fun resetElementPositions() {
        updateTemplate {
            it.copy(
                usernameX = CardLayoutMetrics.DEFAULT_USERNAME_X,
                usernameY = CardLayoutMetrics.DEFAULT_USERNAME_Y,
                passwordX = CardLayoutMetrics.DEFAULT_PASSWORD_X,
                passwordY = CardLayoutMetrics.DEFAULT_PASSWORD_Y,
                qrX = CardLayoutMetrics.DEFAULT_QR_X,
                qrY = CardLayoutMetrics.DEFAULT_QR_Y
            )
        }
        _uiState.update { it.copy(statusMessage = "تمت إعادة ضبط موقع رقم الكرت والعناصر إلى المنتصف.") }
    }

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
                val autoCols = CardLayoutMetrics.calculateFittingColumns(parsed)
                val rows = current.activeTemplate.rowsPerPage.coerceIn(1, 30)
                val updatedTpl = CardLayoutMetrics.sanitize(
                    current.activeTemplate.copy(
                        cardWidth = parsed,
                        columnsPerPage = autoCols
                    )
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
                val autoRows = CardLayoutMetrics.calculateFittingRows(parsed)
                val updatedTpl = CardLayoutMetrics.sanitize(
                    current.activeTemplate.copy(
                        cardHeight = parsed,
                        rowsPerPage = autoRows
                    )
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
                val updatedTpl = CardLayoutMetrics.sanitize(current.activeTemplate.copy(columnsPerPage = parsed))
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
                val updatedTpl = CardLayoutMetrics.sanitize(current.activeTemplate.copy(rowsPerPage = parsed))
                current.copy(
                    rowsInput = filtered,
                    activeTemplate = updatedTpl,
                    cardsPerA4Page = updatedTpl.columnsPerPage * parsed
                )
            }
        }
    }

    fun openFullA4Preview() {
        val tpl = CardLayoutMetrics.sanitize(_uiState.value.activeTemplate)
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

    fun clearBackgroundImage() {
        updateTemplate { it.copy(backgroundImagePath = null) }
    }

    fun onElementDragged(element: DesignerElement, deltaRelX: Float, deltaRelY: Float) {
        if (!deltaRelX.isFinite() || !deltaRelY.isFinite()) return
        _uiState.update { state ->
            val t = CardLayoutMetrics.sanitize(state.activeTemplate)
            val updated = when (element) {
                DesignerElement.USERNAME -> t.copy(
                    usernameX = (t.usernameX + deltaRelX).coerceIn(CardLayoutMetrics.MIN_REL_X, CardLayoutMetrics.MAX_REL_X),
                    usernameY = (t.usernameY + deltaRelY).coerceIn(CardLayoutMetrics.MIN_REL_Y, CardLayoutMetrics.MAX_REL_Y)
                )
                DesignerElement.PASSWORD -> t.copy(
                    passwordX = (t.passwordX + deltaRelX).coerceIn(CardLayoutMetrics.MIN_REL_X, CardLayoutMetrics.MAX_REL_X),
                    passwordY = (t.passwordY + deltaRelY).coerceIn(CardLayoutMetrics.MIN_REL_Y, CardLayoutMetrics.MAX_REL_Y)
                )
                DesignerElement.QR_CODE -> t.copy(
                    qrX = (t.qrX + deltaRelX).coerceIn(CardLayoutMetrics.MIN_QR_REL_X, CardLayoutMetrics.MAX_QR_REL_X),
                    qrY = (t.qrY + deltaRelY).coerceIn(CardLayoutMetrics.MIN_QR_REL_Y, CardLayoutMetrics.MAX_QR_REL_Y)
                )
            }
            state.copy(selectedElement = element, activeTemplate = updated)
        }
    }

    /**
     * Copies the picked image into an independent UUID-named file dedicated exclusively
     * to the active template so images never overwrite or bleed across different templates.
     */
    fun copyPickedBackgroundImage(context: Context, uri: Uri) {
        viewModelScope.launch {
            val savedPath = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.filesDir, "card_templates").apply { mkdirs() }
                    val uniqueFile = File(dir, "tpl_bg_${UUID.randomUUID()}.png")
                    val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                    if (decoded != null) {
                        FileOutputStream(uniqueFile).use { out ->
                            decoded.compress(Bitmap.CompressFormat.PNG, 100, out)
                            out.flush()
                        }
                        decoded.recycle()
                        uniqueFile.takeIf { it.exists() && it.length() > 0L }?.absolutePath
                    } else {
                        // Fallback raw byte copy if decoder returned null
                        val rawFile = File(dir, "tpl_bg_${UUID.randomUUID()}.jpg")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(rawFile).use { output ->
                                input.copyTo(output)
                                output.flush()
                            }
                        }
                        rawFile.takeIf { it.exists() && it.length() > 0L }?.absolutePath
                    }
                }.getOrNull()
            }
            if (savedPath != null) {
                val hadNoBackgroundBefore = _uiState.value.activeTemplate.backgroundImagePath.isNullOrBlank()
                val wasDefaultWhite = _uiState.value.activeTemplate.textColorHex.equals("#FFFFFF", ignoreCase = true)
                val autoSwitchToDark = hadNoBackgroundBefore && wasDefaultWhite

                updateTemplate { tpl ->
                    tpl.copy(
                        backgroundImagePath = savedPath,
                        textColorHex = if (autoSwitchToDark) "#000000" else tpl.textColorHex
                    )
                }
                val msg = if (autoSwitchToDark) {
                    "تم تعيين صورة القالب وضبط لون النص إلى الأسود لوضوح رقم الكرت (اضغط حفظ القالب لتثبيت التعديلات)."
                } else {
                    "تم تعيين صورة خلفية مستقلة لهذا القالب (اضغط حفظ القالب لتثبيت التعديلات)."
                }
                _uiState.update { it.copy(statusMessage = msg) }
            } else {
                _uiState.update { it.copy(errorMessage = "تعذر قراءة الصورة المختارة.") }
            }
        }
    }

    fun saveActiveTemplate() {
        val raw = _uiState.value.activeTemplate
        if (raw.name.isBlank()) {
            _uiState.update { it.copy(errorMessage = "يرجى إدخال اسم القالب أولاً.") }
            return
        }
        viewModelScope.launch {
            val sanitized = CardLayoutMetrics.sanitize(
                raw.copy(
                    name = raw.name.trim(),
                    packageName = raw.packageName.trim().ifBlank { "الكل" },
                    updatedAt = System.currentTimeMillis()
                )
            )

            repository.saveTemplate(sanitized)
                .onSuccess { newId ->
                    val persisted = sanitized.copy(id = if (sanitized.id == 0L) newId else sanitized.id)
                    _uiState.update {
                        it.copy(
                            activeTemplate = persisted,
                            widthInput = persisted.cardWidth.toInt().toString(),
                            heightInput = persisted.cardHeight.toInt().toString(),
                            columnsInput = persisted.columnsPerPage.toString(),
                            rowsInput = persisted.rowsPerPage.toString(),
                            cardsPerA4Page = persisted.columnsPerPage * persisted.rowsPerPage,
                            statusMessage = "تم حفظ قالب (${persisted.name}) لباقة (${persisted.packageName}) بكامل الإحداثيات والصورة بنجاح."
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
        val allTemplates = _uiState.value.templates
        if (current.id == 0L) {
            createNewBlankTemplate()
            return
        }
        viewModelScope.launch {
            val imagePathToCheck = current.backgroundImagePath
            repository.deleteTemplate(current)
                .onSuccess {
                    // Safely clean up orphaned template image only if no other template uses it
                    if (!imagePathToCheck.isNullOrBlank()) {
                        val stillReferenced = allTemplates.any {
                            it.id != current.id && it.backgroundImagePath == imagePathToCheck
                        }
                        if (!stillReferenced) {
                            withContext(Dispatchers.IO) {
                                runCatching { File(imagePathToCheck).delete() }
                            }
                        }
                    }
                    val remaining = allTemplates.filter { it.id != current.id }
                    _uiState.update { it.copy(statusMessage = "تم حذف القالب (${current.name}).") }
                    if (remaining.isNotEmpty()) {
                        selectTemplate(remaining.first())
                    } else {
                        createNewBlankTemplate()
                    }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(errorMessage = err.localizedMessage ?: "تعذر حذف القالب") }
                }
        }
    }

    private fun renderFullA4PageBitmap(template: CardTemplateEntity): Bitmap {
        val safeTemplate = CardLayoutMetrics.sanitize(template)
        val pageW = 900
        val pageH = 1273 // ISO A4 ratio 210:297
        val margin = 24f
        val headerH = 38f
        val gap = 3f

        val cols = safeTemplate.columnsPerPage.coerceIn(1, 6)
        val rows = safeTemplate.rowsPerPage.coerceIn(1, 30)
        val perPage = cols * rows

        val cellW = (pageW - margin * 2 - gap * (cols - 1)) / cols
        val ratio = safeTemplate.cardHeight.coerceAtLeast(20f) / safeTemplate.cardWidth.coerceAtLeast(40f)
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

        val pkgLabel = if (safeTemplate.packageName == "الكل") "جميع الباقات" else safeTemplate.packageName
        canvas.drawText("${safeTemplate.name} • $pkgLabel ($perPage كرت/صفحة)", pageW - margin, margin + 18f, headerPaint)
        canvas.drawText("A4 • $cols×$rows", margin, margin + 18f, metaPaint)

        val sampleCard = CardEntity(
            username = "15650109767",
            password = "4821",
            packageName = safeTemplate.packageName
        )
        val preloadedBg = CardBitmapRenderer.decodeBackgroundBitmap(safeTemplate)
        val cardBmp = try {
            CardBitmapRenderer.renderCardBitmap(
                card = sampleCard,
                template = safeTemplate,
                widthPx = (cellW * 2f).roundToInt().coerceIn(220, 620),
                heightPx = (cellH * 2f).roundToInt().coerceIn(70, 360),
                preloadedBackground = preloadedBg
            )
        } finally {
            preloadedBg?.recycle()
        }
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
                cardsPerA4Page = next.columnsPerPage.coerceIn(1, 6) * next.rowsPerPage.coerceIn(1, 30)
            )
        }
    }
}
