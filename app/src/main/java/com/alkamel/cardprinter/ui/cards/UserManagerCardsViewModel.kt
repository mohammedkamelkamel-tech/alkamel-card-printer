package com.alkamel.cardprinter.ui.cards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alkamel.cardprinter.data.local.entity.CardEntity
import com.alkamel.cardprinter.data.local.entity.CardPackageEntity
import com.alkamel.cardprinter.data.local.entity.CardTemplateEntity
import com.alkamel.cardprinter.data.local.model.CardStatus
import com.alkamel.cardprinter.data.repository.CardPrinterRepository
import com.alkamel.cardprinter.data.session.PrintQueueHolder
import com.alkamel.cardprinter.data.util.CardImportParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val CATEGORY_ALL = "الكل"
const val CATEGORY_UNPRINTED = "كل الكروت غير المطبوعة"

data class UserManagerCardsUiState(
    val allCards: List<CardEntity> = emptyList(),
    val filteredCards: List<CardEntity> = emptyList(),
    val packages: List<CardPackageEntity> = emptyList(),
    val templates: List<CardTemplateEntity> = emptyList(),
    val selectedCardIds: Set<Long> = emptySet(),
    val printDateQuery: String = "",
    val appliedPrintDate: String = "",
    val usernameQuery: String = "",
    val selectedStatus: CardStatus? = null,
    val selectedCategory: String = CATEGORY_ALL,
    val isImporting: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

class UserManagerCardsViewModel(
    private val repository: CardPrinterRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(UserManagerCardsUiState())
    val uiState: StateFlow<UserManagerCardsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                repository.observeAllCards(),
                repository.observeAllPackages(),
                repository.observeAllTemplates()
            ) { cards, pkgs, templates ->
                Triple(cards, pkgs, templates)
            }.collect { (cards, pkgs, templates) ->
                _uiState.update { current ->
                    val validIds = cards.map { it.id }.toSet()
                    val updated = current.copy(
                        allCards = cards,
                        packages = pkgs,
                        templates = templates,
                        selectedCardIds = current.selectedCardIds.intersect(validIds)
                    )
                    updated.copy(filteredCards = applyFilters(updated))
                }
            }
        }
    }

    fun onPrintDateQueryChange(value: String) {
        _uiState.update { it.copy(printDateQuery = value) }
    }

    fun applyPrintDateSearch() {
        _uiState.update { current ->
            val updated = current.copy(appliedPrintDate = current.printDateQuery.trim())
            updated.copy(filteredCards = applyFilters(updated))
        }
    }

    fun clearDateSearch() {
        _uiState.update { current ->
            val updated = current.copy(printDateQuery = "", appliedPrintDate = "")
            updated.copy(filteredCards = applyFilters(updated))
        }
    }

    fun onUsernameQueryChange(value: String) {
        _uiState.update { current ->
            val updated = current.copy(usernameQuery = value)
            updated.copy(filteredCards = applyFilters(updated))
        }
    }

    fun onStatusSelected(status: CardStatus?) {
        _uiState.update { current ->
            val updated = current.copy(selectedStatus = status)
            updated.copy(filteredCards = applyFilters(updated))
        }
    }

    fun onCategorySelected(category: String) {
        _uiState.update { current ->
            val updated = current.copy(selectedCategory = category)
            updated.copy(filteredCards = applyFilters(updated))
        }
    }

    fun toggleCardSelection(cardId: Long) {
        _uiState.update { current ->
            val next = if (cardId in current.selectedCardIds) {
                current.selectedCardIds - cardId
            } else {
                current.selectedCardIds + cardId
            }
            current.copy(selectedCardIds = next)
        }
    }

    fun toggleSelectAllFiltered() {
        _uiState.update { current ->
            val filteredIds = current.filteredCards.map { it.id }.toSet()
            val allSelected = filteredIds.isNotEmpty() && filteredIds.all { it in current.selectedCardIds }
            current.copy(selectedCardIds = if (allSelected) emptySet() else filteredIds)
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
    }

    fun reAddSelectedCards() {
        val targetCards = getActionTargetCards()
        if (targetCards.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "لا توجد كروت محددة لإعادة الإضافة.") }
            return
        }
        viewModelScope.launch {
            repository.resetCardsToUnused(targetCards.map { it.id })
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            selectedCardIds = emptySet(),
                            statusMessage = "تمت إعادة إضافة ${targetCards.size} كرت بنجاح (غير مستخدم/جاهز للطباعة)."
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(errorMessage = err.localizedMessage ?: "تعذرت إعادة الإضافة") }
                }
        }
    }

    fun deleteSelectedCards() {
        val targetCards = getActionTargetCards()
        if (targetCards.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "لا توجد كروت محددة للحذف.") }
            return
        }
        viewModelScope.launch {
            repository.deleteCardsByIds(targetCards.map { it.id })
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            selectedCardIds = emptySet(),
                            statusMessage = "تم حذف ${targetCards.size} كرت بنجاح."
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(errorMessage = err.localizedMessage ?: "تعذر حذف الكروت") }
                }
        }
    }

    fun dispatchCardsToA4Print(): Boolean {
        val state = _uiState.value
        val targetCards = getActionTargetCards()
        if (targetCards.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "لا توجد كروت للطباعة في القائمة الحالية.") }
            return false
        }
        val firstPkg = targetCards.first().packageName
        val matchingTemplate = state.templates.firstOrNull {
            it.packageName.equals(firstPkg, ignoreCase = true)
        } ?: state.templates.firstOrNull { it.isDefault } ?: state.templates.firstOrNull()

        PrintQueueHolder.setQueue(cards = targetCards, template = matchingTemplate)
        return true
    }

    fun importCardsFromFile(rawContent: String, fileName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, errorMessage = null) }
            val state = _uiState.value
            val existingUsernames = state.allCards.map { it.username }.toSet()
            val defaultPkg = state.packages.firstOrNull()
            val result = CardImportParser.parseTextOrCsv(
                rawContent = rawContent,
                defaultPackageName = defaultPkg?.name ?: "80",
                defaultPrice = defaultPkg?.price ?: 100.0,
                defaultPointOfSale = defaultPkg?.pointOfSale ?: "ether3",
                sourceFileName = fileName,
                existingUsernames = existingUsernames
            )
            if (result.parsedCards.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        errorMessage = "لم يتم العثور على كروت جديدة صالحة (تم تجاهل ${result.skippedDuplicates} مكرر)."
                    )
                }
                return@launch
            }
            repository.insertCardsIgnoringDuplicates(result.parsedCards)
                .onSuccess { inserted ->
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            statusMessage = "تم استيراد $inserted كرت جديد من ($fileName) • تم منع ${result.skippedDuplicates} كرت مكرر."
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            errorMessage = err.localizedMessage ?: "تعذر حفظ الكروت المستوردة"
                        )
                    }
                }
        }
    }

    fun getActionTargetCount(): Int = getActionTargetCards().size

    private fun getActionTargetCards(): List<CardEntity> {
        val state = _uiState.value
        return if (state.selectedCardIds.isNotEmpty()) {
            state.filteredCards.filter { it.id in state.selectedCardIds }
        } else {
            state.filteredCards
        }
    }

    private fun applyFilters(state: UserManagerCardsUiState): List<CardEntity> {
        return state.allCards.filter { card ->
            val matchStatus = state.selectedStatus == null || card.status == state.selectedStatus
            val matchCategory = when (state.selectedCategory) {
                CATEGORY_ALL -> true
                CATEGORY_UNPRINTED -> !card.isPrinted
                else -> card.packageName.equals(state.selectedCategory, ignoreCase = true)
            }
            val matchDate = state.appliedPrintDate.isBlank() ||
                (card.printedDateKey?.contains(state.appliedPrintDate, ignoreCase = true) == true)
            val matchQuery = state.usernameQuery.isBlank() ||
                card.username.contains(state.usernameQuery, ignoreCase = true) ||
                card.pointOfSale.contains(state.usernameQuery, ignoreCase = true)
            matchStatus && matchCategory && matchDate && matchQuery
        }
    }
}
