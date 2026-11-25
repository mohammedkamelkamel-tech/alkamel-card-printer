package com.alkamel.cardprinter.ui.cards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alkamel.cardprinter.data.local.entity.CardEntity
import com.alkamel.cardprinter.data.local.entity.CardPackageEntity
import com.alkamel.cardprinter.data.local.entity.CardSessionEntity
import com.alkamel.cardprinter.data.local.entity.CardTemplateEntity
import com.alkamel.cardprinter.data.local.model.CardStatus
import com.alkamel.cardprinter.data.mikrotik.MikrotikSyncManager
import com.alkamel.cardprinter.data.repository.CardPrinterRepository
import com.alkamel.cardprinter.data.session.LocalSessionHolder
import com.alkamel.cardprinter.data.session.PrintQueueHolder
import com.alkamel.cardprinter.data.util.CardImportParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val CATEGORY_ALL = "الكل"
const val CATEGORY_UNPRINTED = "كل الكروت غير المطبوعة"

data class UserManagerCardsUiState(
    val totalCardsCount: Int = 0,
    val allCards: List<CardEntity> = emptyList(),
    val filteredCards: List<CardEntity> = emptyList(),
    val packages: List<CardPackageEntity> = emptyList(),
    val availableCategories: List<String> = listOf(CATEGORY_ALL, CATEGORY_UNPRINTED),
    val templates: List<CardTemplateEntity> = emptyList(),
    val selectedCardIds: Set<Long> = emptySet(),
    val printDateQuery: String = "",
    val appliedPrintDate: String = "",
    val usernameQuery: String = "",
    val selectedStatus: CardStatus? = null,
    val selectedCategory: String = CATEGORY_ALL,
    val isConnectedToMikrotik: Boolean = false,
    val isSyncing: Boolean = false,
    val isImporting: Boolean = false,
    val inspectedCard: CardEntity? = null,
    val inspectedCardSessions: List<CardSessionEntity> = emptyList(),
    val isLoadingCardSessions: Boolean = false,
    val cardSessionsError: String? = null,
    val cardSessionsSourceNote: String = "",
    val statusMessage: String? = null,
    val errorMessage: String? = null
) {
    val hasActiveFilter: Boolean
        get() = selectedStatus != null ||
            selectedCategory != CATEGORY_ALL ||
            appliedPrintDate.isNotBlank() ||
            usernameQuery.isNotBlank()
}

class UserManagerCardsViewModel(
    private val repository: CardPrinterRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(UserManagerCardsUiState())
    val uiState: StateFlow<UserManagerCardsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                repository.observeCardCounts(),
                repository.observePackageCardCounts(),
                repository.observeAllPackages()
            ) { counts, pkgCounts, pkgs ->
                Triple(counts, pkgCounts, pkgs)
            }.collect { (counts, pkgCounts, pkgs) ->
                val dynamicCategories = (pkgs.map { it.name } + pkgCounts.map { it.packageName })
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                val categories = listOf(CATEGORY_ALL, CATEGORY_UNPRINTED) + dynamicCategories
                _uiState.update { latest ->
                    latest.copy(
                        totalCardsCount = counts.totalCount,
                        packages = pkgs,
                        availableCategories = categories
                    )
                }
            }
        }

        viewModelScope.launch {
            combine(
                LocalSessionHolder.activeSession,
                repository.observeAllCards(),
                repository.observeAllTemplates()
            ) { session, cards, templates ->
                CardsBundle(session?.isConnectedToMikrotik == true, cards, templates)
            }.collect { bundle ->
                val current = _uiState.value
                val (validIds, filtered, refreshedInspected) = withContext(Dispatchers.Default) {
                    val ids = if (current.selectedCardIds.isEmpty()) {
                        emptySet()
                    } else {
                        val allIds = HashSet<Long>(bundle.cards.size)
                        bundle.cards.forEach { allIds.add(it.id) }
                        current.selectedCardIds.filterTo(HashSet()) { it in allIds }
                    }
                    val inspected = current.inspectedCard?.let { cur ->
                        bundle.cards.firstOrNull { it.id == cur.id } ?: cur
                    }
                    val previewState = current.copy(allCards = bundle.cards)
                    Triple(ids, applyFilters(previewState), inspected)
                }
                _uiState.update { latest ->
                    val updated = latest.copy(
                        isConnectedToMikrotik = bundle.connected,
                        totalCardsCount = if (latest.totalCardsCount > 0) latest.totalCardsCount else bundle.cards.size,
                        allCards = bundle.cards,
                        templates = bundle.templates,
                        selectedCardIds = validIds,
                        inspectedCard = refreshedInspected,
                        filteredCards = if (
                            latest.selectedStatus == current.selectedStatus &&
                            latest.selectedCategory == current.selectedCategory &&
                            latest.appliedPrintDate == current.appliedPrintDate &&
                            latest.usernameQuery == current.usernameQuery
                        ) {
                            filtered
                        } else {
                            applyFilters(latest.copy(allCards = bundle.cards))
                        }
                    )
                    updated
                }
            }
        }
    }

    fun onPrintDateQueryChange(value: String) = _uiState.update { it.copy(printDateQuery = value) }

    fun applyPrintDateSearch() {
        updateFiltersAsync { it.copy(appliedPrintDate = it.printDateQuery.trim()) }
    }

    fun clearDateSearch() {
        updateFiltersAsync { it.copy(printDateQuery = "", appliedPrintDate = "") }
    }

    fun onUsernameQueryChange(value: String) {
        updateFiltersAsync { it.copy(usernameQuery = value) }
    }

    fun onStatusSelected(status: CardStatus?) {
        updateFiltersAsync { it.copy(selectedStatus = status) }
    }

    fun onCategorySelected(category: String) {
        updateFiltersAsync { it.copy(selectedCategory = category) }
    }

    private fun updateFiltersAsync(transform: (UserManagerCardsUiState) -> UserManagerCardsUiState) {
        _uiState.update(transform)
        val snapshot = _uiState.value
        viewModelScope.launch {
            val filtered = withContext(Dispatchers.Default) { applyFilters(snapshot) }
            _uiState.update { latest ->
                if (
                    latest.selectedStatus == snapshot.selectedStatus &&
                    latest.selectedCategory == snapshot.selectedCategory &&
                    latest.appliedPrintDate == snapshot.appliedPrintDate &&
                    latest.usernameQuery == snapshot.usernameQuery &&
                    latest.allCards === snapshot.allCards
                ) {
                    latest.copy(filteredCards = filtered)
                } else {
                    latest
                }
            }
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

    fun openCardDetails(card: CardEntity) {
        _uiState.update {
            it.copy(
                inspectedCard = card,
                inspectedCardSessions = emptyList(),
                isLoadingCardSessions = true,
                cardSessionsError = null,
                cardSessionsSourceNote = "جاري تحميل جلسات الكرت..."
            )
        }
        viewModelScope.launch {
            val cached = repository.getSessionsForUserOnce(card.username)
            if (cached.isNotEmpty()) {
                _uiState.update {
                    if (it.inspectedCard?.username == card.username) {
                        it.copy(
                            inspectedCardSessions = cached,
                            cardSessionsSourceNote = "عرض الجلسات المحفوظة محلياً • جاري التحديث من المايكروتيك..."
                        )
                    } else it
                }
            }
            fetchSessionsLiveFor(card)
        }
    }

    fun refreshInspectedCardSessions() {
        val card = _uiState.value.inspectedCard ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingCardSessions = true,
                    cardSessionsError = null,
                    cardSessionsSourceNote = "جاري جلب الجلسات مباشرة من المايكروتيك..."
                )
            }
            fetchSessionsLiveFor(card)
        }
    }

    fun closeCardDetails() {
        _uiState.update {
            it.copy(
                inspectedCard = null,
                inspectedCardSessions = emptyList(),
                isLoadingCardSessions = false,
                cardSessionsError = null,
                cardSessionsSourceNote = ""
            )
        }
    }

    private suspend fun fetchSessionsLiveFor(card: CardEntity) {
        val session = LocalSessionHolder.activeSession.value
        if (session == null || session.hostAddress.isBlank() || !session.isConnectedToMikrotik) {
            val cached = repository.getSessionsForUserOnce(card.username)
            _uiState.update {
                if (it.inspectedCard?.username == card.username) {
                    it.copy(
                        inspectedCardSessions = cached,
                        isLoadingCardSessions = false,
                        cardSessionsSourceNote = if (cached.isNotEmpty()) {
                            "غير متصل بالمايكروتيك — يتم عرض الجلسات المحفوظة محلياً"
                        } else {
                            "غير متصل بالمايكروتيك"
                        }
                    )
                } else it
            }
            return
        }

        val res = MikrotikSyncManager.fetchSessionsForUser(
            credentials = session.toCredentials(),
            loadType = session.loadType,
            username = card.username,
            repository = repository
        )
        res.onSuccess { liveSessions ->
            _uiState.update {
                if (it.inspectedCard?.username == card.username) {
                    it.copy(
                        inspectedCardSessions = liveSessions,
                        isLoadingCardSessions = false,
                        cardSessionsError = null,
                        cardSessionsSourceNote = "تم التحديث مباشرة من المايكروتيك (${liveSessions.size} جلسة)"
                    )
                } else it
            }
        }.onFailure { err ->
            val cached = repository.getSessionsForUserOnce(card.username)
            _uiState.update {
                if (it.inspectedCard?.username == card.username) {
                    it.copy(
                        inspectedCardSessions = cached,
                        isLoadingCardSessions = false,
                        cardSessionsError = err.localizedMessage ?: "تعذر جلب الجلسات من المايكروتيك.",
                        cardSessionsSourceNote = if (cached.isNotEmpty()) "تم عرض آخر جلسات محفوظة محلياً" else ""
                    )
                } else it
            }
        }
    }

    fun dispatchSingleCardToA4Print(card: CardEntity): Boolean {
        val state = _uiState.value
        val matchingTemplate = com.alkamel.cardprinter.data.printing.CardLayoutMetrics.selectBestTemplateForPackage(
            templates = state.templates,
            packageName = card.packageName
        )

        PrintQueueHolder.setQueue(cards = listOf(card), template = matchingTemplate)
        return true
    }

    fun deleteSingleCard(card: CardEntity) {
        viewModelScope.launch {
            val session = LocalSessionHolder.activeSession.value
            var mikrotikNote = ""
            if (session != null && session.isConnectedToMikrotik) {
                val remoteRes = MikrotikSyncManager.deleteCardsOnMikrotik(
                    credentials = session.toCredentials(),
                    loadType = session.loadType,
                    cards = listOf(card)
                )
                if (remoteRes.isSuccess && remoteRes.getOrDefault(0) > 0) {
                    mikrotikNote = " (وحُذف من المايكروتيك)"
                }
            }
            repository.deleteCardsByIds(listOf(card.id))
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            inspectedCard = null,
                            inspectedCardSessions = emptyList(),
                            selectedCardIds = it.selectedCardIds - card.id,
                            statusMessage = "تم حذف الكرت ${card.username}$mikrotikNote."
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(errorMessage = err.localizedMessage ?: "تعذر حذف الكرت") }
                }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
    }

    fun syncFromMikrotik() {
        val session = LocalSessionHolder.activeSession.value
        if (session == null || session.hostAddress.isBlank()) {
            _uiState.update { it.copy(errorMessage = "لا يوجد اتصال فعلي بالمايكروتيك.") }
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
                        statusMessage = "تم تحديث جميع بيانات يوزر منجر (${summary.cardsSynced} كرت • ${summary.packagesSynced} باقة)."
                    )
                }
            }.onFailure { err ->
                LocalSessionHolder.updateConnectionStatus(false, null, err.localizedMessage)
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        errorMessage = err.localizedMessage ?: "تعذرت المزامنة مع المايكروتيك."
                    )
                }
            }
        }
    }

    fun reAddSelectedCards() {
        val targetCards = getActionTargetCards()
        if (targetCards.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "لا توجد كروت محددة لإعادة الإضافة.") }
            return
        }
        viewModelScope.launch {
            val session = LocalSessionHolder.activeSession.value
            if (session != null && session.isConnectedToMikrotik) {
                MikrotikSyncManager.pushCardsToMikrotik(
                    credentials = session.toCredentials(),
                    loadType = session.loadType,
                    cards = targetCards,
                    repository = repository,
                    onProgress = { }
                )
            }
            repository.resetCardsToUnused(targetCards.map { it.id })
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            selectedCardIds = emptySet(),
                            statusMessage = "تمت إعادة إضافة ${targetCards.size} كرت بنجاح."
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
            val session = LocalSessionHolder.activeSession.value
            var mikrotikNote = ""
            if (session != null && session.isConnectedToMikrotik) {
                val remoteRes = MikrotikSyncManager.deleteCardsOnMikrotik(
                    credentials = session.toCredentials(),
                    loadType = session.loadType,
                    cards = targetCards
                )
                if (remoteRes.isSuccess) {
                    mikrotikNote = " (وحُذف ${remoteRes.getOrDefault(0)} من المايكروتيك)"
                }
            }
            repository.deleteCardsByIds(targetCards.map { it.id })
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            selectedCardIds = emptySet(),
                            statusMessage = "تم حذف ${targetCards.size} كرت$mikrotikNote."
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
        val matchingTemplate = com.alkamel.cardprinter.data.printing.CardLayoutMetrics.selectBestTemplateForPackage(
            templates = state.templates,
            packageName = firstPkg
        )

        PrintQueueHolder.setQueue(cards = targetCards, template = matchingTemplate)
        return true
    }

    fun importCardsFromFile(rawContent: String, fileName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, errorMessage = null) }
            val state = _uiState.value
            val existingUsernames = repository.getAllUsernamesOnce().toSet()
            val defaultPkg = state.packages.firstOrNull()
            val serverAccount = LocalSessionHolder.activeSession.value?.username.orEmpty()
            val result = withContext(Dispatchers.Default) {
                CardImportParser.parseTextOrCsv(
                    rawContent = rawContent,
                    defaultPackageName = defaultPkg?.name.orEmpty(),
                    defaultPrice = defaultPkg?.price ?: 0.0,
                    serverAccount = serverAccount,
                    sourceFileName = fileName,
                    existingUsernames = existingUsernames
                )
            }
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
                card.packageName.contains(state.usernameQuery, ignoreCase = true)
            matchStatus && matchCategory && matchDate && matchQuery
        }
    }

    private data class CardsBundle(
        val connected: Boolean,
        val cards: List<CardEntity>,
        val templates: List<CardTemplateEntity>
    )
}
