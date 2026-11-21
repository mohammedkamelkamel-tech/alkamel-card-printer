package com.alkamel.cardprinter.data.session

import com.alkamel.cardprinter.data.local.model.NetworkLoadType
import com.alkamel.cardprinter.data.mikrotik.MikrotikCredentials
import com.alkamel.cardprinter.data.mikrotik.MikrotikSystemStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveSession(
    val networkName: String = "",
    val hostAddress: String = "",
    val port: Int = 8728,
    val username: String = "",
    val password: String = "",
    val loadType: NetworkLoadType = NetworkLoadType.LOAD_USER_MANAGER,
    val isConnectedToMikrotik: Boolean = false,
    val connectionError: String? = null,
    val systemStatus: MikrotikSystemStatus? = null,
    val shouldPromptFastSessionLoad: Boolean = false
) {
    fun toCredentials(): MikrotikCredentials = MikrotikCredentials(
        host = hostAddress,
        port = port,
        username = username,
        password = password
    )
}

object LocalSessionHolder {
    private val _activeSession = MutableStateFlow<ActiveSession?>(null)
    val activeSession: StateFlow<ActiveSession?> = _activeSession.asStateFlow()

    fun setSession(session: ActiveSession) {
        _activeSession.value = session
    }

    fun updateConnectionStatus(
        isConnected: Boolean,
        systemStatus: MikrotikSystemStatus? = null,
        errorMessage: String? = null
    ) {
        _activeSession.value = _activeSession.value?.copy(
            isConnectedToMikrotik = isConnected,
            systemStatus = systemStatus ?: if (!isConnected) null else _activeSession.value?.systemStatus,
            connectionError = errorMessage
        )
    }

    fun markFastSessionPromptHandled() {
        _activeSession.value = _activeSession.value?.copy(shouldPromptFastSessionLoad = false)
    }

    fun clearSession() {
        _activeSession.value = null
    }
}
