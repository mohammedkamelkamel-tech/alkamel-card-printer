package com.alkamel.cardprinter.data.util

import com.alkamel.cardprinter.data.local.entity.CardEntity
import com.alkamel.cardprinter.data.local.model.CardStatus
import java.security.SecureRandom
import java.util.UUID

enum class GenerationCharset(val arabicLabel: String) {
    NUMBERS_ONLY("ارقام فقط"),
    ALPHANUMERIC("ارقام وحروف")
}

data class CardGenerationConfig(
    val accountName: String = "admin",
    val charset: GenerationCharset = GenerationCharset.NUMBERS_ONLY,
    val usernameOnly: Boolean = true,
    val packageId: Long? = null,
    val packageName: String = "80",
    val price: Double = 100.0,
    val commission: Double = 0.0,
    val pointOfSale: String = "",
    val count: Int = 100,
    val usernameLength: Int = 10,
    val passwordLength: Int = 4,
    val prefix: String = "",
    val suffix: String = "",
    val bindFirstDevice: Boolean = false
)

object CardGeneratorEngine {

    private const val DIGITS = "0123456789"
    private const val ALPHANUM = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val secureRandom = SecureRandom()

    fun generateCards(
        config: CardGenerationConfig,
        existingUsernames: Set<String>
    ): List<CardEntity> {
        val targetCount = config.count.coerceIn(1, 2000)
        val bodyLength = config.usernameLength.coerceIn(3, 24)
        val passLength = config.passwordLength.coerceIn(2, 16)
        val alphabet = if (config.charset == GenerationCharset.NUMBERS_ONLY) DIGITS else ALPHANUM

        val batchId = "BATCH_${UUID.randomUUID().toString().take(8).uppercase()}"
        val now = System.currentTimeMillis()
        val seen = HashSet<String>(existingUsernames.size + targetCount).apply {
            addAll(existingUsernames)
        }

        val generated = ArrayList<CardEntity>(targetCount)
        val maxAttempts = targetCount * 25
        var attempts = 0

        while (generated.size < targetCount && attempts < maxAttempts) {
            attempts++
            val randomBody = randomString(alphabet, bodyLength)
            val username = "${config.prefix.trim()}$randomBody${config.suffix.trim()}"
            if (username.isBlank() || !seen.add(username)) continue

            val password = if (config.usernameOnly) "" else randomString(alphabet, passLength)

            generated.add(
                CardEntity(
                    id = 0L,
                    username = username,
                    password = password,
                    packageId = config.packageId,
                    packageName = config.packageName.trim(),
                    price = config.price.coerceAtLeast(0.0),
                    commission = config.commission.coerceAtLeast(0.0),
                    pointOfSale = config.pointOfSale.trim(),
                    status = CardStatus.UNUSED,
                    isPrinted = false,
                    printedAt = null,
                    printedDateKey = null,
                    uptimeLabel = "0m",
                    uptimeMinutes = 0L,
                    dataUsageLabel = "0 MB",
                    dataUsageMb = 0.0,
                    bindFirstDevice = config.bindFirstDevice,
                    sourceFileName = null,
                    batchId = batchId,
                    createdAt = now + generated.size
                )
            )
        }
        return generated
    }

    private fun randomString(alphabet: String, length: Int): String {
        val sb = StringBuilder(length)
        for (i in 0 until length) {
            val index = if (i == 0 && alphabet == DIGITS && length > 1) {
                1 + secureRandom.nextInt(alphabet.length - 1)
            } else {
                secureRandom.nextInt(alphabet.length)
            }
            sb.append(alphabet[index])
        }
        return sb.toString()
    }
}
