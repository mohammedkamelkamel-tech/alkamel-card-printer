package com.alkamel.cardprinter.ui.expired

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alkamel.cardprinter.data.local.entity.CardEntity
import com.alkamel.cardprinter.data.mikrotik.MikrotikSyncManager
import com.alkamel.cardprinter.data.repository.CardPrinterRepository
import com.alkamel.cardprinter.data.session.LocalSessionHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExpiredCardsUiState(
    val allExpired: List<CardEntity> = emptyList(),
    val filteredExpired: List<CardEntity> = emptyList(),
    val searchQuery: String = "",
    val isConnectedToMikrotik: Boolean = false,
    val isSyncing: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

class ExpiredCardsViewModel(
    private val repository: CardPrinterRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExpiredCardsUiState())
    val uiState: StateFlow<ExpiredCardsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                LocalSessionHolder.activeSession,
                repository.observeExpiredCards()
            ) { session, expired ->
                (session?.isConnectedToMikrotik == true) to expired
            }.collect { (connected, expired) ->
                _uiState.update { current ->
                    current.copy(
                        isConnectedToMikrotik = connected,
                        allExpired = expired,
                        filteredExpired = filterExpired(expired, current.searchQuery)
                    )
                }
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { current ->
            current.copy(
                searchQuery = query,
                filteredExpired = filterExpired(current.allExpired, query)
            )
        }
    }

    fun clearMessages() = _uiState.update { it.copy(statusMessage = null, errorMessage = null) }

    fun syncFromMikrotik() {
        val session = LocalSessionHolder.activeSession.value
        if (session == null || !session.hasConfiguredRouter) {
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
                onProgress = { _, _ -> },
                onSystemStatusReady = { liveStatus ->
                    LocalSessionHolder.updateConnectionStatus(true, liveStatus, null)
                }
            ).onSuccess { summary ->
                LocalSessionHolder.updateConnectionStatus(true, summary.systemStatus, null)
                LocalSessionHolder.updateLiveReportsMetrics(summary.liveReportsMetrics)
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        statusMessage = "تم تحديث الكروت المنتهية من يوزر منجر (${summary.cardsSynced} كرت إجمالي)."
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        errorMessage = err.localizedMessage ?: "تعذرت المزامنة مع المايكروتيك."
                    )
                }
            }
        }
    }

    fun deleteSingleCard(card: CardEntity) {
        viewModelScope.launch {
            val session = LocalSessionHolder.activeSession.value
            if (session != null && session.hasConfiguredRouter) {
                val res = MikrotikSyncManager.deleteCardsOnMikrotik(
                    credentials = session.toCredentials(),
                    loadType = session.loadType,
                    cards = listOf(card)
                )
                if (res.isSuccess) {
                    LocalSessionHolder.updateConnectionStatus(isConnected = true, errorMessage = null)
                }
            }
            repository.deleteCardsByIds(listOf(card.id))
                .onSuccess {
                    _uiState.update { it.copy(statusMessage = "تم حذف الكرت المنتهي (${card.username}).") }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(errorMessage = err.localizedMessage ?: "تعذر الحذف") }
                }
        }
    }

    fun restoreCard(card: CardEntity) {
        viewModelScope.launch {
            repository.resetCardsToUnused(listOf(card.id))
                .onSuccess {
                    _uiState.update { it.copy(statusMessage = "تمت إعادة تفعيل الكرت (${card.username}).") }
                }
        }
    }

    fun deleteAllExpired() {
        val targets = _uiState.value.filteredExpired
        if (targets.isEmpty()) return
        viewModelScope.launch {
            val session = LocalSessionHolder.activeSession.value
            if (session != null && session.hasConfiguredRouter) {
                val res = MikrotikSyncManager.deleteCardsOnMikrotik(
                    credentials = session.toCredentials(),
                    loadType = session.loadType,
                    cards = targets
                )
                if (res.isSuccess) {
                    LocalSessionHolder.updateConnectionStatus(isConnected = true, errorMessage = null)
                }
            }
            repository.deleteCardsByIds(targets.map { it.id })
                .onSuccess {
                    _uiState.update { it.copy(statusMessage = "تم تنظيف وحذف ${targets.size} كرت منتهي بنجاح.") }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(errorMessage = err.localizedMessage ?: "تعذر الحذف") }
                }
        }
    }

    private fun filterExpired(items: List<CardEntity>, query: String): List<CardEntity> {
        val q = query.trim()
        if (q.isBlank()) return items
        return items.filter {
            it.username.contains(q, ignoreCase = true) ||
                it.packageName.contains(q, ignoreCase = true)
        }
    }
}
