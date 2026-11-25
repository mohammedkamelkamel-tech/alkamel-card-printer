package com.alkamel.cardprinter.ui.packages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alkamel.cardprinter.data.local.entity.CardPackageEntity
import com.alkamel.cardprinter.data.mikrotik.MikrotikSyncManager
import com.alkamel.cardprinter.data.repository.CardPrinterRepository
import com.alkamel.cardprinter.data.session.LocalSessionHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PackagesUiState(
    val packages: List<CardPackageEntity> = emptyList(),
    val cardCountsByPackage: Map<String, Int> = emptyMap(),
    val isConnectedToMikrotik: Boolean = false,
    val isSyncing: Boolean = false,
    val showEditorDialog: Boolean = false,
    val editingId: Long = 0L,
    val nameInput: String = "",
    val priceInput: String = "",
    val commissionInput: String = "0",
    val validityInput: String = "",
    val dataLimitMbInput: String = "0",
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
            combine(
                LocalSessionHolder.activeSession,
                repository.observeAllPackages(),
                repository.observePackageCardCounts()
            ) { session, list, counts ->
                val countMap = counts.associate { it.packageName.trim().lowercase() to it.cardsCount }
                Triple(session?.isConnectedToMikrotik == true, list, countMap)
            }.collect { (connected, list, countMap) ->
                _uiState.update {
                    it.copy(
                        isConnectedToMikrotik = connected,
                        packages = list,
                        cardCountsByPackage = countMap
                    )
                }
            }
        }
    }

    fun syncFromMikrotik() {
        val session = LocalSessionHolder.activeSession.value
        if (session == null || session.hostAddress.isBlank()) {
            _uiState.update { it.copy(errorMessage = "لا يوجد اتصال فعلي بسيرفر يوزر منجر.") }
            return
        }
        if (_uiState.value.isSyncing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, errorMessage = null) }
            MikrotikSyncManager.syncFromMikrotik(
                credentials = session.toCredentials(),
                loadType = session.loadType,
                repository = repository,
                onProgress = { _, _ -> }
            ).onSuccess { summary ->
                LocalSessionHolder.updateConnectionStatus(true, summary.systemStatus, null)
                LocalSessionHolder.updateLiveReportsMetrics(summary.liveReportsMetrics)
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        statusMessage = "تم تحديث باقات وكروت يوزر منجر (${summary.packagesSynced} باقة • ${summary.cardsSynced} كرت)."
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        errorMessage = err.localizedMessage ?: "تعذر مزامنة الباقات من المايكروتيك."
                    )
                }
            }
        }
    }

    fun openAddDialog() {
        _uiState.update {
            it.copy(
                showEditorDialog = true,
                editingId = 0L,
                nameInput = "",
                priceInput = "",
                commissionInput = "0",
                validityInput = "",
                dataLimitMbInput = "0"
            )
        }
    }

    fun openEditDialog(pkg: CardPackageEntity) {
        _uiState.update {
            it.copy(
                showEditorDialog = true,
                editingId = pkg.id,
                nameInput = pkg.name,
                priceInput = if (pkg.price > 0) pkg.price.toLong().toString() else "",
                commissionInput = pkg.commission.toLong().toString(),
                validityInput = pkg.validityLabel,
                dataLimitMbInput = pkg.dataLimitMb.toLong().toString()
            )
        }
    }

    fun dismissDialog() = _uiState.update { it.copy(showEditorDialog = false) }
    fun onNameChange(v: String) = _uiState.update { it.copy(nameInput = v) }
    fun onPriceChange(v: String) = _uiState.update { it.copy(priceInput = v.filter { c -> c.isDigit() || c == '.' }) }
    fun onCommissionChange(v: String) = _uiState.update { it.copy(commissionInput = v.filter { c -> c.isDigit() || c == '.' }) }
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
                price = s.priceInput.toDoubleOrNull() ?: 0.0,
                commission = s.commissionInput.toDoubleOrNull() ?: 0.0,
                validityLabel = s.validityInput.trim(),
                dataLimitMb = s.dataLimitMbInput.toDoubleOrNull() ?: 0.0
            )
            repository.savePackage(entity)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            showEditorDialog = false,
                            statusMessage = "تم حفظ الباقة ($name) وتحديث أسعار كروتها بنجاح."
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
