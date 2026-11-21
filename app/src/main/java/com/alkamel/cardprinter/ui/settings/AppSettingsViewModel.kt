package com.alkamel.cardprinter.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alkamel.cardprinter.data.preferences.AppPreferencesManager
import com.alkamel.cardprinter.data.preferences.AppSettingsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppSettingsUiState(
    val settings: AppSettingsState = AppSettingsState(),
    val newPasswordInput: String = "",
    val confirmPasswordInput: String = "",
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

class AppSettingsViewModel(
    private val preferencesManager: AppPreferencesManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppSettingsUiState())
    val uiState: StateFlow<AppSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesManager.settingsFlow.collect { s ->
                _uiState.update { it.copy(settings = s) }
            }
        }
    }

    fun onToggleCutGuides(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.saveA4Preferences(
                showCutGuides = enabled,
                showHeader = _uiState.value.settings.showHeaderOnA4
            )
            _uiState.update { it.copy(statusMessage = "تم تحديث إعداد خطوط القص على ورق A4.") }
        }
    }

    fun onToggleA4Header(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.saveA4Preferences(
                showCutGuides = _uiState.value.settings.showCutGuidesOnA4,
                showHeader = enabled
            )
            _uiState.update { it.copy(statusMessage = "تم تحديث ترويسة صفحة A4.") }
        }
    }

    fun onNewPasswordChange(v: String) = _uiState.update { it.copy(newPasswordInput = v, errorMessage = null) }
    fun onConfirmPasswordChange(v: String) = _uiState.update { it.copy(confirmPasswordInput = v, errorMessage = null) }
    fun clearMessages() = _uiState.update { it.copy(statusMessage = null, errorMessage = null) }

    fun enableAppLock() {
        val s = _uiState.value
        val pwd = s.newPasswordInput.trim()
        if (pwd.length < 4) {
            _uiState.update { it.copy(errorMessage = "يجب أن تتكون كلمة المرور من 4 أحرف أو أرقام على الأقل.") }
            return
        }
        if (pwd != s.confirmPasswordInput.trim()) {
            _uiState.update { it.copy(errorMessage = "كلمتا المرور غير متطابقتين.") }
            return
        }
        viewModelScope.launch {
            preferencesManager.enableAppLock(pwd, biometric = s.settings.biometricEnabled)
            _uiState.update {
                it.copy(
                    newPasswordInput = "",
                    confirmPasswordInput = "",
                    statusMessage = "تم تفعيل قفل التطبيق بنجاح."
                )
            }
        }
    }

    fun toggleBiometric(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setBiometricEnabled(enabled)
            _uiState.update { it.copy(statusMessage = if (enabled) "تم تفعيل فتح التطبيق بالبصمة." else "تم إيقاف الفتح بالبصمة.") }
        }
    }

    fun disableAppLock() {
        viewModelScope.launch {
            preferencesManager.disableAppLock()
            _uiState.update { it.copy(statusMessage = "تم إلغاء قفل التطبيق.") }
        }
    }
}
