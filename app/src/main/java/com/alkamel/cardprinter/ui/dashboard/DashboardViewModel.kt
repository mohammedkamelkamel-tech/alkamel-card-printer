package com.alkamel.cardprinter.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alkamel.cardprinter.data.local.model.CardStatus
import com.alkamel.cardprinter.data.mikrotik.MikrotikSyncManager
import com.alkamel.cardprinter.data.repository.CardPrinterRepository
import com.alkamel.cardprinter.data.session.LocalSessionHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val totalCardsCount: Int = 0,
    val unusedCardsCount: Int = 0,
    val expiredCardsCount: Int = 0,
    val showFastSessionPrompt: Boolean = false,
    val isLoadingSessions: Boolean = false,
    val sessionLoadProgress: Int = 0,
    val sessionLoadLabel: String = "غير متصل بالمايكروتيك"
)

class DashboardViewModel(
    private val repository: CardPrinterRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

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
                        totalCardsCount = cards.size,
                        unusedCardsCount = cards.count { it.status == CardStatus.UNUSED },
                        expiredCardsCount = cards.count { it.status == CardStatus.EXPIRED },
                        showFastSessionPrompt = current.showFastSessionPrompt ||
                            (connected && session?.shouldPromptFastSessionLoad == true),
                        sessionLoadLabel = when {
                            current.isLoadingSessions -> current.sessionLoadLabel
                            connected -> "متصل بالمايكروتيك ($hostLabel)"
                            else -> "غير متصل بالمايكروتيك — لا توجد قراءات مباشرة"
                        }
                    )
                }
            }
        }
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

    private fun syncRealDataFromMikrotik() {
        val session = LocalSessionHolder.activeSession.value
        if (session == null || session.hostAddress.isBlank()) {
            _uiState.update {
                it.copy(
                    isConnectedToMikrotik = false,
                    connectionError = "لا توجد بيانات اتصال بالمايكروتيك. يرجى تسجيل الدخول أولاً.",
                    sessionLoadLabel = "غير متصل بالمايكروتيك",
                    sessionLoadProgress = 0
                )
            }
            return
        }
        if (_uiState.value.isLoadingSessions) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingSessions = true,
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
                        isLoadingSessions = false,
                        sessionLoadProgress = 100,
                        sessionLoadLabel = "تمت المزامنة: ${summary.cardsSynced} كرت • ${summary.packagesSynced} باقة"
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
                        sessionLoadProgress = 0,
                        connectionError = msg,
                        sessionLoadLabel = "فشل الاتصال بالمايكروتيك"
                    )
                }
            }
        }
    }
}
