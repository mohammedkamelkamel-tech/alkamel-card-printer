package com.alkamel.cardprinter.data.util

import com.alkamel.cardprinter.data.local.entity.CardEntity
import com.alkamel.cardprinter.data.local.model.CardStatus

data class CardImportResult(
    val parsedCards: List<CardEntity>,
    val skippedDuplicates: Int,
    val skippedInvalid: Int
)

object CardImportParser {

    fun parseTextOrCsv(
        rawContent: String,
        defaultPackageName: String,
        defaultPrice: Double,
        serverAccount: String,
        sourceFileName: String,
        existingUsernames: Set<String>
    ): CardImportResult {
        val seen = HashSet<String>(existingUsernames)
        val cards = mutableListOf<CardEntity>()
        var duplicates = 0
        var invalid = 0
        val now = System.currentTimeMillis()

        rawContent.lineSequence().forEachIndexed { idx, rawLine ->
            val line = rawLine.trim().removePrefix("\uFEFF")
            if (line.isBlank()) return@forEachIndexed

            val parts = line.split(',', ';', '\t', '|').map { it.trim().trim('"') }
            val firstCol = parts.firstOrNull().orEmpty()
            if (idx == 0 && (firstCol.equals("username", true) || firstCol.equals("user", true) || firstCol.contains("اسم"))) {
                return@forEachIndexed
            }

            if (firstCol.isBlank()) {
                invalid++
                return@forEachIndexed
            }
            if (!seen.add(firstCol)) {
                duplicates++
                return@forEachIndexed
            }

            val password = parts.getOrNull(1).orEmpty()
            val pkg = parts.getOrNull(2)?.ifBlank { defaultPackageName } ?: defaultPackageName
            val price = parts.getOrNull(3)?.toDoubleOrNull() ?: defaultPrice

            cards.add(
                CardEntity(
                    username = firstCol,
                    password = password,
                    packageName = pkg,
                    price = price,
                    serverAccount = serverAccount,
                    status = CardStatus.UNUSED,
                    sourceFileName = sourceFileName,
                    createdAt = now + cards.size
                )
            )
        }

        return CardImportResult(
            parsedCards = cards,
            skippedDuplicates = duplicates,
            skippedInvalid = invalid
        )
    }
}
