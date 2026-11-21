package com.alkamel.cardprinter.ui.expired

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alkamel.cardprinter.data.local.entity.CardEntity
import com.alkamel.cardprinter.data.local.model.CardStatus
import com.alkamel.cardprinter.data.repository.CardPrinterRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExpiredCardsUiState(
    val allExpired: List<CardEntity> = emptyList(),
    val filteredExpired: List<CardEntity> = emptyList(),
    val searchQuery: String = "",
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
            repository.observeAllCards().collect { cards ->
                val expired = cards.filter { it.status == CardStatus.EXPIRED }
                _uiState.update { current ->
                    current.copy(
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

    fun deleteSingleCard(card: CardEntity) {
        viewModelScope.launch {
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
        val ids = _uiState.value.filteredExpired.map { it.id }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.deleteCardsByIds(ids)
                .onSuccess {
                    _uiState.update { it.copy(statusMessage = "تم تنظيف وحذف ${ids.size} كرت منتهي بنجاح.") }
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
                it.packageName.contains(q, ignoreCase = true) ||
                it.pointOfSale.contains(q, ignoreCase = true)
        }
    }
}
