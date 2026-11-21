package com.alkamel.cardprinter.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alkamel.cardprinter.data.local.entity.SavedNetworkEntity
import com.alkamel.cardprinter.data.local.model.NetworkLoadType
import com.alkamel.cardprinter.data.mikrotik.MikrotikCredentials
import com.alkamel.cardprinter.data.mikrotik.MikrotikSyncManager
import com.alkamel.cardprinter.data.repository.CardPrinterRepository
import com.alkamel.cardprinter.data.session.ActiveSession
import com.alkamel.cardprinter.data.session.LocalSessionHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val networkName: String = "",
    val hostAddress: String = "",
    val port: String = "8728",
    val username: String = "admin",
    val password: String = "",
    val selectedLoadType: NetworkLoadType = NetworkLoadType.LOAD_USER_MANAGER,
    val savedNetworks: List<SavedNetworkEntity> = emptyList(),
    val showSavedNetworksSheet: Boolean = false,
    val isConnecting: Boolean = false,
    val errorMessage: String? = null,
    val statusMessage: String? = null
)

class LoginViewModel(
    private val repository: CardPrinterRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAllNetworks().collect { networks ->
                _uiState.update { current ->
                    val shouldPrefill = current.savedNetworks.isEmpty() && networks.isNotEmpty()
                    val defaultNet = networks.firstOrNull { it.isDefault } ?: networks.firstOrNull()
                    if (shouldPrefill && defaultNet != null) {
                        current.copy(
                            savedNetworks = networks,
                            networkName = defaultNet.networkName,
                            hostAddress = defaultNet.hostAddress,
                            port = defaultNet.port.toString(),
                            username = defaultNet.username,
                            password = defaultNet.password,
                            selectedLoadType = defaultNet.loadType
                        )
                    } else {
                        current.copy(savedNetworks = networks)
                    }
                }
            }
        }
    }

    fun onNetworkNameChange(value: String) = _uiState.update { it.copy(networkName = value, errorMessage = null) }
    fun onHostAddressChange(value: String) = _uiState.update { it.copy(hostAddress = value, errorMessage = null) }
    fun onPortChange(value: String) = _uiState.update { it.copy(port = value.filter { ch -> ch.isDigit() }.take(5), errorMessage = null) }
    fun onUsernameChange(value: String) = _uiState.update { it.copy(username = value, errorMessage = null) }
    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, errorMessage = null) }
    fun onLoadTypeChange(type: NetworkLoadType) = _uiState.update { it.copy(selectedLoadType = type) }
    fun setShowSavedNetworksSheet(show: Boolean) = _uiState.update { it.copy(showSavedNetworksSheet = show) }
    fun clearMessages() = _uiState.update { it.copy(errorMessage = null, statusMessage = null) }

    fun selectSavedNetwork(network: SavedNetworkEntity) {
        _uiState.update {
            it.copy(
                networkName = network.networkName,
                hostAddress = network.hostAddress,
                port = network.port.toString(),
                username = network.username,
                password = network.password,
                selectedLoadType = network.loadType,
                showSavedNetworksSheet = false,
                errorMessage = null,
                statusMessage = "تم اختيار الشبكة: ${network.networkName}"
            )
        }
    }

    fun saveCurrentNetwork() {
        val state = _uiState.value
        val host = state.hostAddress.trim()
        if (host.isBlank()) {
            _uiState.update { it.copy(errorMessage = "يرجى إدخال عنوان سيرفر المايكروتيك (IP / Host) أولاً.") }
            return
        }
        val name = state.networkName.trim().ifBlank { "شبكة ($host)" }
        val parsedPort = state.port.toIntOrNull()?.coerceIn(1, 65535) ?: 8728
        val user = state.username.trim().ifBlank { "admin" }

        viewModelScope.launch {
            val existing = state.savedNetworks.firstOrNull {
                it.hostAddress.equals(host, ignoreCase = true) && it.username.equals(user, ignoreCase = true)
            }
            val entity = SavedNetworkEntity(
                id = existing?.id ?: 0L,
                networkName = name,
                hostAddress = host,
                port = parsedPort,
                username = user,
                password = state.password,
                loadType = state.selectedLoadType,
                isDefault = existing?.isDefault ?: state.savedNetworks.isEmpty(),
                lastUsedAt = System.currentTimeMillis()
            )
            repository.saveNetwork(entity)
                .onSuccess {
                    _uiState.update { it.copy(statusMessage = "تم حفظ بيانات الشبكة ($name) بنجاح.") }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(errorMessage = err.localizedMessage ?: "تعذر حفظ الشبكة") }
                }
        }
    }

    fun deleteSavedNetwork(network: SavedNetworkEntity) {
        viewModelScope.launch {
            repository.deleteNetwork(network)
                .onSuccess {
                    _uiState.update { it.copy(statusMessage = "تم حذف الشبكة: ${network.networkName}") }
                }
        }
    }

    fun connectToMikrotikAndLogin(onSuccess: () -> Unit) {
        val state = _uiState.value
        val host = state.hostAddress.trim()
        if (host.isBlank()) {
            _uiState.update { it.copy(errorMessage = "يرجى إدخال عنوان سيرفر المايكروتيك (IP / Host).") }
            return
        }
        val parsedPort = state.port.toIntOrNull()?.takeIf { it in 1..65535 }
        if (parsedPort == null) {
            _uiState.update { it.copy(errorMessage = "رقم المنفذ (Port) غير صحيح.") }
            return
        }
        val user = state.username.trim()
        if (user.isBlank()) {
            _uiState.update { it.copy(errorMessage = "يرجى إدخال اسم المستخدم للمايكروتيك.") }
            return
        }
        val netName = state.networkName.trim().ifBlank { host }

        viewModelScope.launch {
            _uiState.update { it.copy(isConnecting = true, errorMessage = null, statusMessage = null) }

            val credentials = MikrotikCredentials(
                host = host,
                port = parsedPort,
                username = user,
                password = state.password
            )
            val testResult = MikrotikSyncManager.testConnectionAndFetchStatus(credentials)

            if (!testResult.isConnected || testResult.systemStatus == null) {
                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        errorMessage = testResult.errorMessage
                            ?: "فشل الاتصال بالمايكروتيك ($host:$parsedPort). تأكد من الشبكة وبيانات الدخول."
                    )
                }
                return@launch
            }

            persistNetworkRecord(state, netName, host, parsedPort, user)

            LocalSessionHolder.setSession(
                ActiveSession(
                    networkName = netName,
                    hostAddress = host,
                    port = parsedPort,
                    username = user,
                    password = state.password,
                    loadType = state.selectedLoadType,
                    isConnectedToMikrotik = true,
                    connectionError = null,
                    systemStatus = testResult.systemStatus,
                    shouldPromptFastSessionLoad = state.selectedLoadType != NetworkLoadType.NO_LOAD
                )
            )
            _uiState.update { it.copy(isConnecting = false) }
            onSuccess()
        }
    }

    fun enterOfflineViewMode(onSuccess: () -> Unit) {
        val state = _uiState.value
        val host = state.hostAddress.trim()
        val parsedPort = state.port.toIntOrNull()?.coerceIn(1, 65535) ?: 8728
        val user = state.username.trim()
        val netName = state.networkName.trim().ifBlank { host.ifBlank { "وضع بدون اتصال" } }

        LocalSessionHolder.setSession(
            ActiveSession(
                networkName = netName,
                hostAddress = host,
                port = parsedPort,
                username = user,
                password = state.password,
                loadType = state.selectedLoadType,
                isConnectedToMikrotik = false,
                connectionError = "غير متصل بسيرفر المايكروتيك حالياً — القراءات المباشرة غير متاحة حتى يتم الربط.",
                systemStatus = null,
                shouldPromptFastSessionLoad = false
            )
        )
        onSuccess()
    }

    private suspend fun persistNetworkRecord(
        state: LoginUiState,
        netName: String,
        host: String,
        port: Int,
        user: String
    ) {
        val existing = state.savedNetworks.firstOrNull {
            it.hostAddress.equals(host, ignoreCase = true) && it.username.equals(user, ignoreCase = true)
        }
        repository.saveNetwork(
            SavedNetworkEntity(
                id = existing?.id ?: 0L,
                networkName = netName,
                hostAddress = host,
                port = port,
                username = user,
                password = state.password,
                loadType = state.selectedLoadType,
                isDefault = true,
                lastUsedAt = System.currentTimeMillis()
            )
        )
    }
}
