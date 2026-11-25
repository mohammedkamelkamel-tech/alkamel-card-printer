package com.alkamel.cardprinter.ui.generation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alkamel.cardprinter.data.local.entity.CardPackageEntity
import com.alkamel.cardprinter.data.local.entity.CardTemplateEntity
import com.alkamel.cardprinter.data.local.model.CardCountsSummary
import com.alkamel.cardprinter.data.local.model.NetworkLoadType
import com.alkamel.cardprinter.data.mikrotik.MikrotikSyncManager
import com.alkamel.cardprinter.data.printing.CardLayoutMetrics
import com.alkamel.cardprinter.data.repository.CardPrinterRepository
import com.alkamel.cardprinter.data.session.ActiveSession
import com.alkamel.cardprinter.data.session.LocalSessionHolder
import com.alkamel.cardprinter.data.session.PrintQueueHolder
import com.alkamel.cardprinter.data.util.CardGenerationConfig
import com.alkamel.cardprinter.data.util.CardGeneratorEngine
import com.alkamel.cardprinter.data.util.GenerationCharset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CardGenerationViewModel(
    private val repository: CardPrinterRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CardGenerationUiState())
    val uiState: StateFlow<CardGenerationUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                LocalSessionHolder.activeSession,
                repository.observeAllPackages(),
                repository.observeAllTemplates(),
                repository.observeCardCounts()
            ) { session, pkgs, templates, counts ->
                GenerationInputs(session, pkgs, templates, counts)
            }.collect { input ->
                val session = input.session
                val connected = session?.isConnectedToMikrotik == true
                val hostLabel = session?.takeIf { it.hostAddress.isNotBlank() }?.let { "${it.hostAddress}:${it.port}" }.orEmpty()

                _uiState.update { current ->
                    val needsInitialPkg = current.selectedPackageName.isBlank() && input.packages.isNotEmpty()
                    val firstPkg = input.packages.firstOrNull()
                    val updatedAccount = session?.username?.ifBlank { current.accountName } ?: current.accountName
                    if (needsInitialPkg && firstPkg != null) {
                        current.copy(
                            accountName = updatedAccount,
                            mikrotikHostLabel = hostLabel,
                            isConnectedToMikrotik = connected,
                            packages = input.packages,
                            templates = input.templates,
                            selectedPackageId = firstPkg.id,
                            selectedPackageName = firstPkg.name,
                            priceInput = if (firstPkg.price > 0) firstPkg.price.toLong().toString() else "",
                            commissionInput = if (firstPkg.commission > 0) firstPkg.commission.toLong().toString() else ""
                        )
                    } else {
                        current.copy(
                            accountName = updatedAccount,
                            mikrotikHostLabel = hostLabel,
                            isConnectedToMikrotik = connected,
                            packages = input.packages,
                            templates = input.templates
                        )
                    }
                }
            }
        }
    }

    fun onAccountChange(value: String) = _uiState.update { it.copy(accountName = value) }
    fun onCharsetChange(charset: GenerationCharset) = _uiState.update { it.copy(charset = charset) }
    fun onUsernameOnlyChange(usernameOnly: Boolean) = _uiState.update { it.copy(usernameOnly = usernameOnly) }
    fun onPackageNameManualChange(value: String) = _uiState.update { it.copy(selectedPackageName = value, selectedPackageId = null) }
    fun onPriceChange(value: String) = _uiState.update { it.copy(priceInput = value.filter { c -> c.isDigit() || c == '.' }) }
    fun onCountChange(value: String) = _uiState.update { it.copy(countInput = value.filter { c -> c.isDigit() }.take(4)) }
    fun onUsernameLengthChange(value: String) = _uiState.update { it.copy(usernameLengthInput = value.filter { c -> c.isDigit() }.take(2)) }
    fun onPasswordLengthChange(value: String) = _uiState.update { it.copy(passwordLengthInput = value.filter { c -> c.isDigit() }.take(2)) }
    fun onCommissionChange(value: String) = _uiState.update { it.copy(commissionInput = value.filter { c -> c.isDigit() || c == '.' }) }
    fun onSuffixChange(value: String) = _uiState.update { it.copy(suffixInput = value.take(8)) }
    fun onPrefixChange(value: String) = _uiState.update { it.copy(prefixInput = value.take(8)) }
    fun onBindFirstDeviceChange(checked: Boolean) = _uiState.update { it.copy(bindFirstDevice = checked) }
    fun clearMessages() = _uiState.update { it.copy(statusMessage = null, errorMessage = null) }

    fun onPackageSelected(pkg: CardPackageEntity) {
        _uiState.update {
            it.copy(
                selectedPackageId = pkg.id,
                selectedPackageName = pkg.name,
                priceInput = if (pkg.price > 0) pkg.price.toLong().toString() else "",
                commissionInput = if (pkg.commission > 0) pkg.commission.toLong().toString() else ""
            )
        }
    }

    fun generateLocalCards() {
        val state = _uiState.value
        val pkgName = state.selectedPackageName.trim()
        if (pkgName.isBlank()) {
            _uiState.update {
                it.copy(errorMessage = "يرجى اختيار باقة من المايكروتيك أو كتابة اسم الباقة أولاً.")
            }
            return
        }
        val count = state.countInput.toIntOrNull()?.coerceIn(1, 2000) ?: 100
        val userLen = state.usernameLengthInput.toIntOrNull()?.coerceIn(3, 24) ?: 10
        val passLen = state.passwordLengthInput.toIntOrNull()?.coerceIn(2, 16) ?: 4
        val price = state.priceInput.toDoubleOrNull() ?: 0.0
        val commission = state.commissionInput.toDoubleOrNull() ?: 0.0

        val config = CardGenerationConfig(
            accountName = state.accountName.ifBlank { "admin" },
            charset = state.charset,
            usernameOnly = state.usernameOnly,
            packageId = state.selectedPackageId,
            packageName = pkgName,
            price = price,
            commission = commission,
            count = count,
            usernameLength = userLen,
            passwordLength = passLen,
            prefix = state.prefixInput.trim(),
            suffix = state.suffixInput.trim(),
            bindFirstDevice = state.bindFirstDevice
        )

        viewModelScope.launch {
            val existingUsernames = repository.getAllUsernamesOnce().toSet()
            val generated = withContext(Dispatchers.Default) {
                CardGeneratorEngine.generateCards(
                    config = config,
                    existingUsernames = existingUsernames
                )
            }

            if (generated.isEmpty()) {
                _uiState.update {
                    it.copy(errorMessage = "تعذر توليد كروت فريدة بهذه الإعدادات. جرّب زيادة طول اسم المستخدم.")
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    generatedCards = generated,
                    isAddedToServer = false,
                    additionCompleted = false,
                    additionProgressPercent = 0,
                    addedCardsCount = 0,
                    failedCardsCount = 0,
                    targetAdditionCount = generated.size,
                    modalErrorMessage = null,
                    statusMessage = "تم توليد ${generated.size} كرت. اضغط «اضافة الى السرفر» لرفعها إلى يوزر منجر."
                )
            }
        }
    }

    fun startAddCardsToServer() {
        val state = _uiState.value
        val cardsToPush = state.generatedCards
        if (cardsToPush.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "قم بتوليد الكروت أولاً قبل الإضافة إلى السيرفر.") }
            return
        }
        val session = LocalSessionHolder.activeSession.value
        if (session == null || !session.hasConfiguredRouter) {
            _uiState.update {
                it.copy(
                    errorMessage = "غير متصل بسيرفر المايكروتيك. يرجى الربط الفعلي بالمايكروتيك أولاً لإضافة الكروت إلى السيرفر."
                )
            }
            return
        }
        if (state.isAddingToServer) return

        viewModelScope.launch {
            val total = cardsToPush.size
            _uiState.update {
                it.copy(
                    showServerAdditionDialog = true,
                    isAddingToServer = true,
                    additionCompleted = false,
                    additionProgressPercent = 1,
                    addedCardsCount = 0,
                    failedCardsCount = 0,
                    targetAdditionCount = total,
                    modalErrorMessage = null,
                    errorMessage = null
                )
            }

            val pushResult = MikrotikSyncManager.pushCardsToMikrotik(
                credentials = session.toCredentials(),
                loadType = NetworkLoadType.LOAD_USER_MANAGER,
                cards = cardsToPush,
                repository = repository,
                onProgress = { prog ->
                    _uiState.update {
                        it.copy(
                            addedCardsCount = prog.addedToRouterCount,
                            failedCardsCount = prog.failedCount,
                            additionProgressPercent = prog.percent
                        )
                    }
                }
            )

            pushResult.onSuccess { pushed ->
                LocalSessionHolder.updateConnectionStatus(isConnected = true, errorMessage = null)
                val pushedUsernames = pushed.map { it.username }.toSet()
                val persistedCards = repository.getAllCardsOnce()
                    .filter { it.username in pushedUsernames }
                val resolvedCards = if (persistedCards.isNotEmpty()) persistedCards else pushed

                _uiState.update {
                    it.copy(
                        generatedCards = resolvedCards,
                        isAddedToServer = true,
                        isAddingToServer = false,
                        additionCompleted = true,
                        additionProgressPercent = 100,
                        addedCardsCount = pushed.size,
                        statusMessage = "تمت إضافة ${pushed.size} من أصل $total كرت فعلياً إلى يوزر منجر بنجاح."
                    )
                }
            }.onFailure { err ->
                val msg = err.localizedMessage ?: "فشل إرسال الكروت إلى سيرفر يوزر منجر."
                _uiState.update {
                    it.copy(
                        isAddingToServer = false,
                        additionCompleted = false,
                        modalErrorMessage = msg,
                        errorMessage = msg
                    )
                }
            }
        }
    }

    fun dismissServerAdditionDialog() {
        if (_uiState.value.isAddingToServer) return
        _uiState.update { it.copy(showServerAdditionDialog = false, modalErrorMessage = null) }
    }

    fun prepareCardsForA4Print(): Boolean {
        val state = _uiState.value
        val cards = state.generatedCards
        if (cards.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "لا توجد كروت مولدة للطباعة. اضغط «توليد» أولاً.") }
            return false
        }
        val pkgName = state.selectedPackageName
        val matchingTemplate = CardLayoutMetrics.selectBestTemplateForPackage(
            templates = state.templates,
            packageName = pkgName
        )

        PrintQueueHolder.setQueue(cards = cards, template = matchingTemplate)
        return true
    }

    private data class GenerationInputs(
        val session: ActiveSession?,
        val packages: List<CardPackageEntity>,
        val templates: List<CardTemplateEntity>,
        val counts: CardCountsSummary
    )
}
