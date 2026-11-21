package com.alkamel.cardprinter.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alkamel.cardprinter.data.local.model.CardStatus
import com.alkamel.cardprinter.data.mikrotik.MikrotikSyncManager
import com.alkamel.cardprinter.data.repository.CardPrinterRepository
import com.alkamel.cardprinter.data.session.LocalSessionHolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DashboardUiState(
    val networkName: String = "",
    val hostPortLabel: String = "",
    val activeUser: String = "",
    val routerOsVersion: String = "",
    val isConnectedToMikrotik: Boolean = false,
    val connectionError: String? = null,
    val cpuPercent: Int? = null,
    val activeConnectionsCount: Int? = null,
    val uptimeDisplay: String? = null,
    val todayDownloadDisplay: String? = null,
    val lastLiveUpdateLabel: String = "",
    val isLiveRefreshing: Boolean = false,
    val totalCardsCount: Int = 0,
    val unusedCardsCount: Int = 0,
    val expiredCardsCount: Int = 0,
    val showFastSessionPrompt: Boolean = false,
    val isLoadingSessions: Boolean = false,
    val isSyncBarVisible: Boolean = false,
    val sessionLoadProgress: Int = 0,
    val sessionLoadLabel: String = "",
    val syncCompletionMessage: String? = null
)

class DashboardViewModel(
    private val repository: CardPrinterRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var livePollingJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                LocalSessionHolder.activeSession,
                repository.observeAllCards()
            ) { session, cards ->
                session to cards
            }.collect { (session, cards) ->
                val status = session?.systemStatus
                val connected = session?.isConnectedToMikrotik == true && status != null
                val hostLabel = if (session != null && session.hostAddress.isNotBlank()) {
                    "${session.hostAddress}:${session.port}"
                } else {
                    "غير محدد"
                }

                _uiState.update { current ->
                    current.copy(
                        networkName = session?.networkName?.ifBlank { hostLabel } ?: "غير متصل",
                        hostPortLabel = hostLabel,
                        activeUser = session?.username.orEmpty(),
                        routerOsVersion = status?.routerOsVersion.orEmpty(),
                        isConnectedToMikrotik = connected,
                        connectionError = session?.connectionError,
                        cpuPercent = if (connected) status?.cpuLoadPercent else null,
                        activeConnectionsCount = if (connected) status?.activeConnectionsCount else null,
                        uptimeDisplay = if (connected) status?.uptimeDisplay else null,
                        todayDownloadDisplay = if (connected) status?.totalDownloadDisplay else null,
                        lastLiveUpdateLabel = if (connected && status != null) {
                            formatTimeShort(status.fetchedAtMillis)
                        } else {
                            ""
                        },
                        totalCardsCount = cards.size,
                        unusedCardsCount = cards.count { it.status == CardStatus.UNUSED },
                        expiredCardsCount = cards.count { it.status == CardStatus.EXPIRED },
                        showFastSessionPrompt = current.showFastSessionPrompt ||
                            (connected && session?.shouldPromptFastSessionLoad == true)
                    )
                }
            }
        }
    }

    /**
     * Periodic live polling every 5 seconds while Dashboard is visible and connected to MikroTik.
     * Fetches real CPU load, active users, uptime, and live download without blocking UI.
     */
    fun startLivePolling() {
        if (livePollingJob?.isActive == true) return
        livePollingJob = viewModelScope.launch {
            while (isActive) {
                val session = LocalSessionHolder.activeSession.value
                if (session != null &&
                    session.hostAddress.isNotBlank() &&
                    session.isConnectedToMikrotik &&
                    !_uiState.value.isLoadingSessions
                ) {
                    _uiState.update { it.copy(isLiveRefreshing = true) }
                    val res = MikrotikSyncManager.fetchLiveTelemetry(session.toCredentials())
                    res.onSuccess { status ->
                        LocalSessionHolder.updateConnectionStatus(
                            isConnected = true,
                            systemStatus = status,
                            errorMessage = null
                        )
                    }.onFailure { err ->
                        // Keep previous telemetry visible transiently unless disconnected repeatedly
                        _uiState.update {
                            it.copy(
                                connectionError = err.localizedMessage
                            )
                        }
                    }
                    _uiState.update { it.copy(isLiveRefreshing = false) }
                }
                delay(5000L)
            }
        }
    }

    fun stopLivePolling() {
        livePollingJob?.cancel()
        livePollingJob = null
    }

    fun dismissFastSessionPrompt() {
        LocalSessionHolder.markFastSessionPromptHandled()
        _uiState.update { it.copy(showFastSessionPrompt = false) }
    }

    fun startFastSessionLoad() {
        LocalSessionHolder.markFastSessionPromptHandled()
        _uiState.update { it.copy(showFastSessionPrompt = false) }
        syncRealDataFromMikrotik()
    }

    fun refreshMikrotikNow() {
        syncRealDataFromMikrotik()
    }

    fun clearSyncCompletionMessage() {
        _uiState.update { it.copy(syncCompletionMessage = null) }
    }

    private fun syncRealDataFromMikrotik() {
        val session = LocalSessionHolder.activeSession.value
        if (session == null || session.hostAddress.isBlank()) {
            _uiState.update {
                it.copy(
                    isConnectedToMikrotik = false,
                    isSyncBarVisible = false,
                    isLoadingSessions = false,
                    sessionLoadProgress = 0,
                    connectionError = "لا توجد بيانات اتصال بالمايكروتيك. يرجى تسجيل الدخول أولاً."
                )
            }
            return
        }
        if (_uiState.value.isLoadingSessions) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingSessions = true,
                    isSyncBarVisible = true,
                    sessionLoadProgress = 5,
                    sessionLoadLabel = "جاري الاتصال بالمايكروتيك...",
                    connectionError = null
                )
            }

            val result = MikrotikSyncManager.syncFromMikrotik(
                credentials = session.toCredentials(),
                loadType = session.loadType,
                repository = repository,
                onProgress = { percent, label ->
                    _uiState.update {
                        it.copy(
                            isSyncBarVisible = true,
                            sessionLoadProgress = percent,
                            sessionLoadLabel = label
                        )
                    }
                }
            )

            result.onSuccess { summary ->
                LocalSessionHolder.updateConnectionStatus(
                    isConnected = true,
                    systemStatus = summary.systemStatus,
                    errorMessage = null
                )
                _uiState.update {
                    it.copy(
                        sessionLoadProgress = 100,
                        sessionLoadLabel = "تم تحديث الكروت والجلسات بالكامل"
                    )
                }
                // Brief pause so user sees 100%, then auto-hide the bottom progress bar completely
                delay(350L)
                _uiState.update {
                    it.copy(
                        isLoadingSessions = false,
                        isSyncBarVisible = false,
                        sessionLoadProgress = 0,
                        sessionLoadLabel = "",
                        syncCompletionMessage = "تم تحديث الكروت والجلسات بالكامل"
                    )
                }
            }.onFailure { err ->
                val msg = err.localizedMessage ?: "تعذر الاتصال بسيرفر المايكروتيك."
                LocalSessionHolder.updateConnectionStatus(
                    isConnected = false,
                    systemStatus = null,
                    errorMessage = msg
                )
                _uiState.update {
                    it.copy(
                        isLoadingSessions = false,
                        isSyncBarVisible = false,
                        sessionLoadProgress = 0,
                        sessionLoadLabel = "",
                        connectionError = msg,
                        syncCompletionMessage = msg
                    )
                }
            }
        }
    }

    override fun onCleared() {
        stopLivePolling()
        super.onCleared()
    }

    private fun formatTimeShort(millis: Long): String {
        return SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(millis))
    }
}
