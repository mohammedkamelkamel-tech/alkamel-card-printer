package com.alkamel.cardprinter.ui.generation

import com.alkamel.cardprinter.data.local.entity.CardEntity
import com.alkamel.cardprinter.data.local.entity.CardPackageEntity
import com.alkamel.cardprinter.data.local.entity.CardTemplateEntity
import com.alkamel.cardprinter.data.util.GenerationCharset

data class CardGenerationUiState(
    val accountName: String = "admin",
    val charset: GenerationCharset = GenerationCharset.NUMBERS_ONLY,
    val usernameOnly: Boolean = true,
    val packages: List<CardPackageEntity> = emptyList(),
    val templates: List<CardTemplateEntity> = emptyList(),
    val selectedPackageId: Long? = null,
    val selectedPackageName: String = "80",
    val priceInput: String = "100",
    val pointOfSale: String = "ether3",
    val countInput: String = "100",
    val usernameLengthInput: String = "10",
    val passwordLengthInput: String = "4",
    val commissionInput: String = "",
    val suffixInput: String = "",
    val prefixInput: String = "1",
    val bindFirstDevice: Boolean = false,
    val generatedCards: List<CardEntity> = emptyList(),
    val isAddedToServer: Boolean = false,
    val showServerAdditionDialog: Boolean = false,
    val isAddingToServer: Boolean = false,
    val additionCompleted: Boolean = false,
    val additionProgressPercent: Int = 0,
    val addedCardsCount: Int = 0,
    val targetAdditionCount: Int = 0,
    val skippedDuplicatesCount: Int = 0,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)
