package com.alkamel.cardprinter.ui.packages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alkamel.cardprinter.data.local.entity.CardPackageEntity
import com.alkamel.cardprinter.data.repository.CardPrinterRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PackagesUiState(
    val packages: List<CardPackageEntity> = emptyList(),
    val showEditorDialog: Boolean = false,
    val editingId: Long = 0L,
    val nameInput: String = "",
    val priceInput: String = "100",
    val commissionInput: String = "0",
    val pointOfSaleInput: String = "ether3",
    val validityInput: String = "يوم واحد",
    val dataLimitMbInput: String = "512",
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

class PackagesViewModel(
    private val repository: CardPrinterRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PackagesUiState())
    val uiState: StateFlow<PackagesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAllPackages().collect { list ->
                _uiState.update { it.copy(packages = list) }
            }
        }
    }

    fun openAddDialog() {
        _uiState.update {
            it.copy(
                showEditorDialog = true,
                editingId = 0L,
                nameInput = "",
                priceInput = "100",
                commissionInput = "0",
                pointOfSaleInput = "ether3",
                validityInput = "يوم واحد",
                dataLimitMbInput = "512"
            )
        }
    }

    fun openEditDialog(pkg: CardPackageEntity) {
        _uiState.update {
            it.copy(
                showEditorDialog = true,
                editingId = pkg.id,
                nameInput = pkg.name,
                priceInput = pkg.price.toLong().toString(),
                commissionInput = pkg.commission.toLong().toString(),
                pointOfSaleInput = pkg.pointOfSale,
                validityInput = pkg.validityLabel,
                dataLimitMbInput = pkg.dataLimitMb.toLong().toString()
            )
        }
    }

    fun dismissDialog() = _uiState.update { it.copy(showEditorDialog = false) }
    fun onNameChange(v: String) = _uiState.update { it.copy(nameInput = v) }
    fun onPriceChange(v: String) = _uiState.update { it.copy(priceInput = v.filter { c -> c.isDigit() || c == '.' }) }
    fun onCommissionChange(v: String) = _uiState.update { it.copy(commissionInput = v.filter { c -> c.isDigit() || c == '.' }) }
    fun onPointOfSaleChange(v: String) = _uiState.update { it.copy(pointOfSaleInput = v) }
    fun onValidityChange(v: String) = _uiState.update { it.copy(validityInput = v) }
    fun onDataLimitChange(v: String) = _uiState.update { it.copy(dataLimitMbInput = v.filter { c -> c.isDigit() || c == '.' }) }
    fun clearMessages() = _uiState.update { it.copy(statusMessage = null, errorMessage = null) }

    fun savePackage() {
        val s = _uiState.value
        val name = s.nameInput.trim()
        if (name.isBlank()) {
            _uiState.update { it.copy(errorMessage = "يرجى إدخال اسم الباقة.") }
            return
        }
        viewModelScope.launch {
            val entity = CardPackageEntity(
                id = s.editingId,
                name = name,
                price = s.priceInput.toDoubleOrNull() ?: 100.0,
                commission = s.commissionInput.toDoubleOrNull() ?: 0.0,
                pointOfSale = s.pointOfSaleInput.trim().ifBlank { "ether3" },
                validityLabel = s.validityInput.trim(),
                dataLimitMb = s.dataLimitMbInput.toDoubleOrNull() ?: 0.0
            )
            repository.savePackage(entity)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            showEditorDialog = false,
                            statusMessage = "تم حفظ الباقة ($name) بنجاح."
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(errorMessage = err.localizedMessage ?: "تعذر حفظ الباقة") }
                }
        }
    }

    fun deletePackage(pkg: CardPackageEntity) {
        viewModelScope.launch {
            repository.deletePackage(pkg)
                .onSuccess {
                    _uiState.update { it.copy(statusMessage = "تم حذف الباقة (${pkg.name}).") }
                }
        }
    }
}
