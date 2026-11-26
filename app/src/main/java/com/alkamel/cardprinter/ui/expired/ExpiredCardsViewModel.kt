package com.alkamel.cardprinter.ui.expired

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alkamel.cardprinter.data.local.entity.CardEntity
import com.alkamel.cardprinter.data.local.model.CardStatus
import com.alkamel.cardprinter.data.mikrotik.MikrotikSyncManager
import com.alkamel.cardprinter.data.repository.CardPrinterRepository
import com.alkamel.cardprinter.data.session.LocalSessionHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val EXPIRED_PACKAGE_ALL = "الكل"

data class ExpiredCardsUiState(
    val unifiedExpiredCount: Int = 0,
    val allCards: List<CardEntity> = emptyList(),
    val allExpired: List<CardEntity> = emptyList(),
    val filteredExpired: List<CardEntity> = emptyList(),
    val availablePackages: List<String> = listOf(EXPIRED_PACKAGE_ALL),
    val selectedPackage: String = EXPIRED_PACKAGE_ALL,
    val minDownloadMbInput: String = "",
    val minUptimeInput: String = "",
    val isThresholdFilterActive: Boolean = false,
    val searchQuery: String = "",
    val selectedCardIds: Set<Long> = emptySet(),
    val isConnectedToMikrotik: Boolean = false,
    val isSyncing: Boolean = false,
    val isDeleting: Boolean = false,
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
                repository.observeCardCounts(),
                repository.observeAllPackages(),
                repository.observePackageCardCounts()
            ) { counts, pkgs, pkgCounts ->
                val names = (pkgs.map { it.name } + pkgCounts.map { it.packageName })
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                counts.expiredCount to (listOf(EXPIRED_PACKAGE_ALL) + names)
            }.collect { (expiredCount, pkgList) ->
                _uiState.update { current ->
                    current.copy(
                        unifiedExpiredCount = expiredCount,
                        availablePackages = pkgList
                    )
                }
            }
        }

        viewModelScope.launch {
            combine(
                LocalSessionHolder.activeSession,
                repository.observeExpiredCards(),
                repository.observeAllCards()
            ) { session, expired, allCards ->
                Triple(session?.isConnectedToMikrotik == true, expired, allCards)
            }.collect { (connected, expired, allCards) ->
                val snapshot = _uiState.value
                val (filtered, validSelected) = withContext(Dispatchers.Default) {
                    val nextState = snapshot.copy(allExpired = expired, allCards = allCards)
                    val list = computeVisibleCards(nextState)
                    val validIds = if (snapshot.selectedCardIds.isEmpty()) {
                        emptySet()
                    } else {
                        val visibleIdSet = list.mapTo(HashSet(list.size)) { it.id }
                        snapshot.selectedCardIds.filterTo(HashSet()) { it in visibleIdSet }
                    }
                    list to validIds
                }
                _uiState.update { current ->
                    val needsRecompute = current.selectedPackage != snapshot.selectedPackage ||
                        current.minDownloadMbInput != snapshot.minDownloadMbInput ||
                        current.minUptimeInput != snapshot.minUptimeInput ||
                        current.isThresholdFilterActive != snapshot.isThresholdFilterActive ||
                        current.searchQuery != snapshot.searchQuery
                    val updated = current.copy(
                        isConnectedToMikrotik = connected,
                        unifiedExpiredCount = maxOf(current.unifiedExpiredCount, expired.size),
                        allExpired = expired,
                        allCards = allCards,
                        selectedCardIds = validSelected
                    )
                    if (needsRecompute) {
                        updated.copy(filteredExpired = computeVisibleCards(updated))
                    } else {
                        updated.copy(filteredExpired = filtered)
                    }
                }
            }
        }
    }

    fun onPackageSelected(pkg: String) {
        recomputeAsync { it.copy(selectedPackage = pkg) }
    }

    fun onMinDownloadMbChange(value: String) {
        val clean = value.filter { it.isDigit() || it == '.' }
        _uiState.update { it.copy(minDownloadMbInput = clean) }
    }

    fun onMinUptimeChange(value: String) {
        _uiState.update { it.copy(minUptimeInput = value) }
    }

    fun onSearchQueryChange(query: String) {
        recomputeAsync { it.copy(searchQuery = query) }
    }

    /**
     * Applies package + download (MB) + uptime threshold search (matches «حذف الكروت المنتهية» screen).
     * Searches across all cards that reached the entered download/uptime limit so expired/consumed
     * cards appear immediately even if RouterOS did not expose a limitation profile.
     */
    fun applyThresholdSearch() {
        val current = _uiState.value
        val hasThreshold = current.minDownloadMbInput.toDoubleOrNull()?.let { it > 0.0 } == true ||
            parseThresholdUptimeMinutes(current.minUptimeInput) > 0L
        recomputeAsync {
            it.copy(
                isThresholdFilterActive = hasThreshold,
                selectedCardIds = emptySet()
            )
        }
    }

    /**
     * Displays all expired User Manager cards (unified with Dashboard «الكروت المنتهية» count).
     */
    fun showAllExpiredCards() {
        recomputeAsync {
            it.copy(
                minDownloadMbInput = "",
                minUptimeInput = "",
                isThresholdFilterActive = false,
                selectedCardIds = emptySet()
            )
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

    fun toggleSelectAll() {
        _uiState.update { current ->
            val allVisibleIds = current.filteredExpired.mapTo(LinkedHashSet()) { it.id }
            val allSelected = allVisibleIds.isNotEmpty() && allVisibleIds.all { it in current.selectedCardIds }
            current.copy(selectedCardIds = if (allSelected) emptySet() else allVisibleIds)
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
            _uiState.update { it.copy(isDeleting = true) }
            val session = LocalSessionHolder.activeSession.value
            var mikrotikNote = ""
            if (session != null && session.hasConfiguredRouter) {
                val res = MikrotikSyncManager.deleteCardsOnMikrotik(
                    credentials = session.toCredentials(),
                    loadType = session.loadType,
                    cards = listOf(card)
                )
                if (res.isSuccess) {
                    LocalSessionHolder.updateConnectionStatus(isConnected = true, errorMessage = null)
                    if (res.getOrDefault(0) > 0) mikrotikNote = " (وحُذف من المايكروتيك)"
                }
            }
            repository.deleteCardsByIds(listOf(card.id))
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isDeleting = false,
                            selectedCardIds = it.selectedCardIds - card.id,
                            statusMessage = "تم حذف الكرت المنتهي (${card.username})$mikrotikNote."
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isDeleting = false,
                            errorMessage = err.localizedMessage ?: "تعذر الحذف"
                        )
                    }
                }
        }
    }

    fun restoreCard(card: CardEntity) {
        viewModelScope.launch {
            repository.resetCardsToUnused(listOf(card.id))
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            selectedCardIds = it.selectedCardIds - card.id,
                            statusMessage = "تمت إعادة تفعيل الكرت (${card.username})."
                        )
                    }
                }
        }
    }

    fun deleteSelectedOrAllExpired() {
        val state = _uiState.value
        val targets = if (state.selectedCardIds.isNotEmpty()) {
            state.filteredExpired.filter { it.id in state.selectedCardIds }
        } else {
            state.filteredExpired
        }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true) }
            val session = LocalSessionHolder.activeSession.value
            var mikrotikNote = ""
            if (session != null && session.hasConfiguredRouter) {
                val res = MikrotikSyncManager.deleteCardsOnMikrotik(
                    credentials = session.toCredentials(),
                    loadType = session.loadType,
                    cards = targets
                )
                if (res.isSuccess) {
                    LocalSessionHolder.updateConnectionStatus(isConnected = true, errorMessage = null)
                    mikrotikNote = " (وحُذف ${res.getOrDefault(0)} من المايكروتيك)"
                }
            }
            repository.deleteCardsByIds(targets.map { it.id })
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isDeleting = false,
                            selectedCardIds = emptySet(),
                            statusMessage = "تم حذف ${targets.size} كرت منتهي بنجاح$mikrotikNote."
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isDeleting = false,
                            errorMessage = err.localizedMessage ?: "تعذر الحذف"
                        )
                    }
                }
        }
    }

    private fun recomputeAsync(transform: (ExpiredCardsUiState) -> ExpiredCardsUiState) {
        _uiState.update(transform)
        val snapshot = _uiState.value
        viewModelScope.launch {
            val computed = withContext(Dispatchers.Default) { computeVisibleCards(snapshot) }
            _uiState.update { latest ->
                if (
                    latest.selectedPackage == snapshot.selectedPackage &&
                    latest.minDownloadMbInput == snapshot.minDownloadMbInput &&
                    latest.minUptimeInput == snapshot.minUptimeInput &&
                    latest.isThresholdFilterActive == snapshot.isThresholdFilterActive &&
                    latest.searchQuery == snapshot.searchQuery &&
                    latest.allCards === snapshot.allCards &&
                    latest.allExpired === snapshot.allExpired
                ) {
                    val visibleIds = computed.mapTo(HashSet(computed.size)) { it.id }
                    latest.copy(
                        filteredExpired = computed,
                        selectedCardIds = latest.selectedCardIds.filterTo(HashSet()) { it in visibleIds }
                    )
                } else {
                    latest
                }
            }
        }
    }

    private fun computeVisibleCards(state: ExpiredCardsUiState): List<CardEntity> {
        val minMb = state.minDownloadMbInput.toDoubleOrNull()?.takeIf { it > 0.0 }
        val minUptimeMin = parseThresholdUptimeMinutes(state.minUptimeInput).takeIf { it > 0L }
        val q = state.searchQuery.trim()
        val pkgFilter = state.selectedPackage

        val source = if (state.isThresholdFilterActive && (minMb != null || minUptimeMin != null)) {
            state.allCards.filter { card ->
                val matchesMb = minMb == null || card.dataUsageMb >= minMb
                val matchesUptime = minUptimeMin == null || card.uptimeMinutes >= minUptimeMin
                (card.status == CardStatus.EXPIRED || (matchesMb && matchesUptime && (card.dataUsageMb > 0.0 || card.uptimeMinutes > 0L))) &&
                    matchesMb && matchesUptime
            }
        } else {
            state.allExpired
        }

        return source.filter { card ->
            val matchPkg = pkgFilter == EXPIRED_PACKAGE_ALL ||
                card.packageName.equals(pkgFilter, ignoreCase = true)
            val matchQuery = q.isBlank() ||
                card.username.contains(q, ignoreCase = true) ||
                card.packageName.contains(q, ignoreCase = true)
            matchPkg && matchQuery
        }
    }

    /**
     * Parses user uptime threshold input: accepts RouterOS duration (`6h`, `1d`, `8h30m`, `06:00:00`)
     * OR plain numeric minutes (`360`).
     */
    private fun parseThresholdUptimeMinutes(raw: String): Long {
        val clean = raw.trim()
        if (clean.isBlank()) return 0L
        clean.toLongOrNull()?.let { return it.coerceAtLeast(0L) }
        return MikrotikSyncManager.parseRouterOsDurationSeconds(clean) / 60L
    }
}
