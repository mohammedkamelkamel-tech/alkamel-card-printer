package com.alkamel.cardprinter.ui.generation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alkamel.cardprinter.data.local.entity.CardEntity
import com.alkamel.cardprinter.data.local.entity.CardPackageEntity
import com.alkamel.cardprinter.data.repository.CardPrinterRepository
import com.alkamel.cardprinter.data.session.LocalSessionHolder
import com.alkamel.cardprinter.data.session.PrintQueueHolder
import com.alkamel.cardprinter.data.util.CardGenerationConfig
import com.alkamel.cardprinter.data.util.CardGeneratorEngine
import com.alkamel.cardprinter.data.util.GenerationCharset
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.ceil

class CardGenerationViewModel(
    private val repository: CardPrinterRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        CardGenerationUiState(
            accountName = LocalSessionHolder.activeSession.value?.username ?: "admin"
        )
    )
    val uiState: StateFlow<CardGenerationUiState> = _uiState.asStateFlow()

    private var cachedExistingUsernames: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            combine(
                repository.observeAllPackages(),
                repository.observeAllTemplates(),
                repository.observeAllCards()
            ) { pkgs, templates, cards ->
                Triple(pkgs, templates, cards)
            }.collect { (pkgs, templates, cards) ->
                cachedExistingUsernames = cards.map { it.username }.toSet()
                _uiState.update { current ->
                    val needsInitialPkg = current.packages.isEmpty() && pkgs.isNotEmpty()
                    val firstPkg = pkgs.firstOrNull()
                    if (needsInitialPkg && firstPkg != null) {
                        current.copy(
                            packages = pkgs,
                            templates = templates,
                            selectedPackageId = firstPkg.id,
                            selectedPackageName = firstPkg.name,
                            priceInput = firstPkg.price.toLong().toString(),
                            pointOfSale = firstPkg.pointOfSale,
                            commissionInput = if (firstPkg.commission > 0) firstPkg.commission.toLong().toString() else ""
                        )
                    } else {
                        current.copy(packages = pkgs, templates = templates)
                    }
                }
            }
        }
    }

    fun onAccountChange(value: String) = _uiState.update { it.copy(accountName = value) }
    fun onCharsetChange(charset: GenerationCharset) = _uiState.update { it.copy(charset = charset) }
    fun onUsernameOnlyChange(usernameOnly: Boolean) = _uiState.update { it.copy(usernameOnly = usernameOnly) }
    fun onPriceChange(value: String) = _uiState.update { it.copy(priceInput = value.filter { c -> c.isDigit() || c == '.' }) }
    fun onPointOfSaleChange(value: String) = _uiState.update { it.copy(pointOfSale = value) }
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
                priceInput = pkg.price.toLong().toString(),
                pointOfSale = pkg.pointOfSale,
                commissionInput = if (pkg.commission > 0) pkg.commission.toLong().toString() else ""
            )
        }
    }

    fun generateLocalCards() {
        val state = _uiState.value
        val count = state.countInput.toIntOrNull()?.coerceIn(1, 2000) ?: 100
        val userLen = state.usernameLengthInput.toIntOrNull()?.coerceIn(3, 24) ?: 10
        val passLen = state.passwordLengthInput.toIntOrNull()?.coerceIn(2, 16) ?: 4
        val price = state.priceInput.toDoubleOrNull() ?: 100.0
        val commission = state.commissionInput.toDoubleOrNull() ?: 0.0

        val config = CardGenerationConfig(
            accountName = state.accountName.ifBlank { "admin" },
            charset = state.charset,
            usernameOnly = state.usernameOnly,
            packageId = state.selectedPackageId,
            packageName = state.selectedPackageName.ifBlank { "80" },
            price = price,
            commission = commission,
            pointOfSale = state.pointOfSale.trim(),
            count = count,
            usernameLength = userLen,
            passwordLength = passLen,
            prefix = state.prefixInput.trim(),
            suffix = state.suffixInput.trim(),
            bindFirstDevice = state.bindFirstDevice
        )

        val generated = CardGeneratorEngine.generateCards(
            config = config,
            existingUsernames = cachedExistingUsernames
        )

        if (generated.isEmpty()) {
            _uiState.update {
                it.copy(errorMessage = "تعذر توليد كروت فريدة بهذه الإعدادات. جرّب زيادة طول اسم المستخدم.")
            }
            return
        }

        _uiState.update {
            it.copy(
                generatedCards = generated,
                isAddedToServer = false,
                additionCompleted = false,
                additionProgressPercent = 0,
                addedCardsCount = 0,
                targetAdditionCount = generated.size,
                skippedDuplicatesCount = 0,
                statusMessage = "تم توليد ${generated.size} كرت محلياً بنجاح. اضغط «اضافة الى السرفر» لحفظها."
            )
        }
    }

    fun startAddCardsToServer() {
        val state = _uiState.value
        val cardsToInsert = state.generatedCards
        if (cardsToInsert.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "قم بتوليد الكروت أولاً قبل الإضافة إلى السيرفر.") }
            return
        }
        if (state.isAddingToServer) return

        viewModelScope.launch {
            val total = cardsToInsert.size
            _uiState.update {
                it.copy(
                    showServerAdditionDialog = true,
                    isAddingToServer = true,
                    additionCompleted = false,
                    additionProgressPercent = 1,
                    addedCardsCount = 0,
                    targetAdditionCount = total,
                    skippedDuplicatesCount = 0,
                    errorMessage = null
                )
            }

            // Divide into responsive real-DB chunks so progress rises smoothly from 1% to 100%
            val chunkSize = maxOf(1, ceil(total / 10.0).toInt())
            val chunks = cardsToInsert.chunked(chunkSize)
            var processed = 0
            var totalInserted = 0
            var hadError: String? = null

            for (chunk in chunks) {
                val res = repository.insertCardsIgnoringDuplicates(chunk)
                if (res.isFailure) {
                    hadError = res.exceptionOrNull()?.localizedMessage ?: "حدث خطأ أثناء الحفظ في قاعدة البيانات"
                    break
                }
                val newlyInserted = res.getOrDefault(0)
                totalInserted += newlyInserted
                processed += chunk.size
                val percent = ((processed * 100f) / total).toInt().coerceIn(1, 99)

                _uiState.update {
                    it.copy(
                        addedCardsCount = totalInserted,
                        additionProgressPercent = percent
                    )
                }
                delay(110L)
            }

            if (hadError != null) {
                _uiState.update {
                    it.copy(
                        isAddingToServer = false,
                        additionCompleted = false,
                        errorMessage = hadError
                    )
                }
                return@launch
            }

            // Reload persisted cards from Room so every card carries its real persisted DB ID for printing
            val targetUsernames = cardsToInsert.map { it.username }.toSet()
            val persistedCards = repository.observeAllCards().first()
                .filter { it.username in targetUsernames }
            val resolvedCards: List<CardEntity> = if (persistedCards.isNotEmpty()) persistedCards else cardsToInsert
            val skipped = (total - totalInserted).coerceAtLeast(0)

            _uiState.update {
                it.copy(
                    generatedCards = resolvedCards,
                    isAddedToServer = true,
                    isAddingToServer = false,
                    additionCompleted = true,
                    additionProgressPercent = 100,
                    addedCardsCount = totalInserted,
                    skippedDuplicatesCount = skipped,
                    statusMessage = "اكتملت إضافة $totalInserted من أصل $total كرت إلى السيرفر (100%)."
                )
            }
        }
    }

    fun dismissServerAdditionDialog() {
        if (_uiState.value.isAddingToServer) return
        _uiState.update { it.copy(showServerAdditionDialog = false) }
    }

    fun prepareCardsForA4Print(): Boolean {
        val state = _uiState.value
        val cards = state.generatedCards
        if (cards.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "لا توجد كروت مولدة للطباعة. اضغط «توليد» أولاً.") }
            return false
        }
        val pkgName = state.selectedPackageName
        val matchingTemplate = state.templates.firstOrNull {
            it.packageName.equals(pkgName, ignoreCase = true)
        } ?: state.templates.firstOrNull { it.isDefault } ?: state.templates.firstOrNull()

        PrintQueueHolder.setQueue(cards = cards, template = matchingTemplate)
        return true
    }
}
