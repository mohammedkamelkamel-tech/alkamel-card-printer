package com.alkamel.cardprinter.ui.designer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alkamel.cardprinter.data.local.entity.CardPackageEntity
import com.alkamel.cardprinter.data.local.entity.CardTemplateEntity
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

data class CardDesignerUiState(
    val templates: List<CardTemplateEntity> = emptyList(),
    val packages: List<CardPackageEntity> = emptyList(),
    val activeTemplate: CardTemplateEntity = CardTemplateEntity(name = "قالب الكامل A4"),
    val widthInput: String = "203",
    val heightInput: String = "61",
    val columnsInput: String = "4",
    val rowsInput: String = "25",
    val selectedElement: DesignerElement = DesignerElement.USERNAME,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

class CardDesignerViewModel(
    private val repository: CardPrinterRepository
) : ViewModel() {

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
                            rowsInput = def.rowsPerPage.toString()
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
                rowsInput = template.rowsPerPage.toString()
            )
        }
    }

    fun createNewBlankTemplate() {
        val fresh = CardTemplateEntity(
            id = 0L,
            name = "قالب جديد ${_uiState.value.templates.size + 1}",
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

    fun onWidthChange(value: String) {
        val filtered = value.filter { it.isDigit() }.take(4)
        val parsed = filtered.toFloatOrNull()?.coerceIn(60f, 500f)
        _uiState.update {
            it.copy(
                widthInput = filtered,
                activeTemplate = if (parsed != null) it.activeTemplate.copy(cardWidth = parsed) else it.activeTemplate
            )
        }
    }

    fun onHeightChange(value: String) {
        val filtered = value.filter { it.isDigit() }.take(4)
        val parsed = filtered.toFloatOrNull()?.coerceIn(30f, 300f)
        _uiState.update {
            it.copy(
                heightInput = filtered,
                activeTemplate = if (parsed != null) it.activeTemplate.copy(cardHeight = parsed) else it.activeTemplate
            )
        }
    }

    fun onColumnsChange(value: String) {
        val filtered = value.filter { it.isDigit() }.take(1)
        val parsed = filtered.toIntOrNull()?.coerceIn(1, 6)
        _uiState.update {
            it.copy(
                columnsInput = filtered,
                activeTemplate = if (parsed != null) it.activeTemplate.copy(columnsPerPage = parsed) else it.activeTemplate
            )
        }
    }

    fun onRowsChange(value: String) {
        val filtered = value.filter { it.isDigit() }.take(2)
        val parsed = filtered.toIntOrNull()?.coerceIn(1, 30)
        _uiState.update {
            it.copy(
                rowsInput = filtered,
                activeTemplate = if (parsed != null) it.activeTemplate.copy(rowsPerPage = parsed) else it.activeTemplate
            )
        }
    }

    fun onToggleShowUsername(show: Boolean) = updateTemplate { it.copy(showUsername = show) }
    fun onToggleShowPassword(show: Boolean) = updateTemplate { it.copy(showPassword = show) }
    fun onToggleShowPointOfSale(show: Boolean) = updateTemplate { it.copy(showPointOfSale = show) }
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
                DesignerElement.POINT_OF_SALE -> t.copy(
                    pointOfSaleX = (t.pointOfSaleX + deltaRelX).coerceIn(0.05f, 0.95f),
                    pointOfSaleY = (t.pointOfSaleY + deltaRelY).coerceIn(0.10f, 0.90f)
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

    private fun updateTemplate(transform: (CardTemplateEntity) -> CardTemplateEntity) {
        _uiState.update { it.copy(activeTemplate = transform(it.activeTemplate)) }
    }
}
