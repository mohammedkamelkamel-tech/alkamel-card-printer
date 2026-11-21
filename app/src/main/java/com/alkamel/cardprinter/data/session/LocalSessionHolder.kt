package com.alkamel.cardprinter.data.session

import com.alkamel.cardprinter.data.local.model.NetworkLoadType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveSession(
    val networkName: String = "شبكة الكامل نت",
    val hostAddress: String = "192.168.88.1",
    val port: Int = 8728,
    val username: String = "admin",
    val loadType: NetworkLoadType = NetworkLoadType.LOAD_USER_MANAGER,
    val shouldPromptFastSessionLoad: Boolean = true
)

object LocalSessionHolder {
    private val _activeSession = MutableStateFlow<ActiveSession?>(null)
    val activeSession: StateFlow<ActiveSession?> = _activeSession.asStateFlow()

    fun setSession(session: ActiveSession) {
        _activeSession.value = session
    }

    fun markFastSessionPromptHandled() {
        _activeSession.value = _activeSession.value?.copy(shouldPromptFastSessionLoad = false)
    }

    fun clearSession() {
        _activeSession.value = null
    }
}
