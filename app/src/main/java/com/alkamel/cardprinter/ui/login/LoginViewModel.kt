package com.alkamel.cardprinter.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alkamel.cardprinter.data.local.entity.SavedNetworkEntity
import com.alkamel.cardprinter.data.local.model.NetworkLoadType
import com.alkamel.cardprinter.data.repository.CardPrinterRepository
import com.alkamel.cardprinter.data.session.ActiveSession
import com.alkamel.cardprinter.data.session.LocalSessionHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val networkName: String = "شبكة الكامل نت",
    val hostAddress: String = "192.168.88.1",
    val port: String = "8728",
    val username: String = "admin",
    val password: String = "",
    val selectedLoadType: NetworkLoadType = NetworkLoadType.LOAD_USER_MANAGER,
    val savedNetworks: List<SavedNetworkEntity> = emptyList(),
    val showSavedNetworksSheet: Boolean = false,
    val isSubmitting: Boolean = false,
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
                statusMessage = "تم اختيار الشبكة: ${network.networkName}"
            )
        }
    }

    fun saveCurrentNetwork() {
        val state = _uiState.value
        val host = state.hostAddress.trim().ifBlank { "192.168.88.1" }
        val name = state.networkName.trim().ifBlank { "شبكة الكامل ($host)" }
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
                    _uiState.update { it.copy(statusMessage = "تم حفظ الشبكة ($name) محلياً بنجاح") }
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

    fun loginLocally(onSuccess: () -> Unit) {
        val state = _uiState.value
        val host = state.hostAddress.trim().ifBlank { "192.168.88.1" }
        val user = state.username.trim().ifBlank { "admin" }
        val parsedPort = state.port.toIntOrNull()?.coerceIn(1, 65535) ?: 8728
        val netName = state.networkName.trim().ifBlank { "شبكة الكامل نت" }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            val existing = state.savedNetworks.firstOrNull {
                it.hostAddress.equals(host, ignoreCase = true) && it.username.equals(user, ignoreCase = true)
            }
            repository.saveNetwork(
                SavedNetworkEntity(
                    id = existing?.id ?: 0L,
                    networkName = netName,
                    hostAddress = host,
                    port = parsedPort,
                    username = user,
                    password = state.password,
                    loadType = state.selectedLoadType,
                    isDefault = true,
                    lastUsedAt = System.currentTimeMillis()
                )
            )
            LocalSessionHolder.setSession(
                ActiveSession(
                    networkName = netName,
                    hostAddress = host,
                    port = parsedPort,
                    username = user,
                    loadType = state.selectedLoadType,
                    shouldPromptFastSessionLoad = state.selectedLoadType != NetworkLoadType.NO_LOAD
                )
            )
            _uiState.update { it.copy(isSubmitting = false) }
            onSuccess()
        }
    }
}
