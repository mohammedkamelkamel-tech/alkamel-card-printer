package com.alkamel.cardprinter.data.repository

import androidx.room.withTransaction
import com.alkamel.cardprinter.data.local.AlkamelDatabase
import com.alkamel.cardprinter.data.local.entity.CardEntity
import com.alkamel.cardprinter.data.local.entity.CardPackageEntity
import com.alkamel.cardprinter.data.local.entity.CardSessionEntity
import com.alkamel.cardprinter.data.local.entity.CardTemplateEntity
import com.alkamel.cardprinter.data.local.entity.PrintSalesLogEntity
import com.alkamel.cardprinter.data.local.entity.SavedNetworkEntity
import com.alkamel.cardprinter.data.local.model.ComprehensiveSalesReport
import com.alkamel.cardprinter.data.local.model.PeriodSalesSummary
import com.alkamel.cardprinter.data.local.model.TopSellingPackageStat
import com.alkamel.cardprinter.data.local.util.DateTimeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CardPrinterRepository(private val database: AlkamelDatabase) {

    private val networkDao get() = database.savedNetworkDao()
    private val packageDao get() = database.cardPackageDao()
    private val cardDao get() = database.cardDao()
    private val templateDao get() = database.cardTemplateDao()
    private val salesDao get() = database.printSalesLogDao()
    private val sessionDao get() = database.cardSessionDao()

    private suspend inline fun <T> safeDbCall(crossinline block: suspend () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun observeAllNetworks(): Flow<List<SavedNetworkEntity>> = networkDao.observeAllNetworks()
    suspend fun saveNetwork(network: SavedNetworkEntity): Result<Long> = safeDbCall { networkDao.upsertNetwork(network) }
    suspend fun deleteNetwork(network: SavedNetworkEntity): Result<Unit> = safeDbCall { networkDao.deleteNetwork(network) }

    fun observeAllPackages(): Flow<List<CardPackageEntity>> = packageDao.observeAllPackages()
    suspend fun getAllPackagesOnce(): List<CardPackageEntity> = packageDao.getAllPackagesOnce()
    suspend fun savePackage(pkg: CardPackageEntity): Result<Long> = safeDbCall { packageDao.upsertPackage(pkg) }
    suspend fun deletePackage(pkg: CardPackageEntity): Result<Unit> = safeDbCall { packageDao.deletePackage(pkg) }

    /**
     * Merges MikroTik profiles into Room while preserving any custom price/commission
     * configured locally by the operator when MikroTik returns 0.0 for price.
     */
    suspend fun mergePackagesFromMikrotik(remotePackages: List<CardPackageEntity>): Result<List<CardPackageEntity>> =
        safeDbCall {
            val existingByName = packageDao.getAllPackagesOnce().associateBy { it.name.lowercase() }
            val merged = remotePackages.map { remote ->
                val existing = existingByName[remote.name.lowercase()]
                if (existing != null) {
                    remote.copy(
                        id = existing.id,
                        price = if (remote.price > 0.0) remote.price else existing.price,
                        commission = if (existing.commission > 0.0) existing.commission else remote.commission,
                        validityLabel = remote.validityLabel.ifBlank { existing.validityLabel },
                        dataLimitMb = if (remote.dataLimitMb > 0.0) remote.dataLimitMb else existing.dataLimitMb
                    )
                } else {
                    remote
                }
            }
            database.withTransaction {
                merged.forEach { packageDao.upsertPackage(it) }
            }
            merged
        }

    fun observeAllCards(): Flow<List<CardEntity>> = cardDao.observeAllCards()

    suspend fun insertCardsIgnoringDuplicates(cards: List<CardEntity>): Result<Int> = safeDbCall {
        if (cards.isEmpty()) return@safeDbCall 0
        var inserted = 0
        database.withTransaction {
            cards.chunked(500).forEach { chunk ->
                inserted += cardDao.insertCardsIgnore(chunk).count { it != -1L }
            }
        }
        inserted
    }

    suspend fun upsertCards(cards: List<CardEntity>): Result<Int> = safeDbCall {
        if (cards.isEmpty()) return@safeDbCall 0
        database.withTransaction {
            val existingByUsername = cardDao.getAllCardsOnce().associateBy { it.username }
            val merged = cards.map { incoming ->
                val existing = existingByUsername[incoming.username]
                if (existing != null) {
                    incoming.copy(
                        id = existing.id,
                        price = if (incoming.price > 0.0) incoming.price else existing.price,
                        isPrinted = existing.isPrinted,
                        printedAt = existing.printedAt,
                        printedDateKey = existing.printedDateKey
                    )
                } else {
                    incoming
                }
            }
            merged.chunked(500).forEach { chunk ->
                cardDao.upsertCards(chunk)
            }
        }
        cards.size
    }

    suspend fun syncCardsFromMikrotik(remoteCards: List<CardEntity>): Result<Int> = upsertCards(remoteCards)

    suspend fun markCardsAsPrinted(cardIds: List<Long>, printedAt: Long, dateKey: String): Result<Unit> = safeDbCall {
        if (cardIds.isEmpty()) return@safeDbCall
        database.withTransaction {
            cardIds.chunked(500).forEach { chunk ->
                cardDao.markCardsPrinted(chunk, printedAt, dateKey)
            }
        }
    }

    suspend fun resetCardsToUnused(cardIds: List<Long>): Result<Unit> = safeDbCall {
        if (cardIds.isEmpty()) return@safeDbCall
        database.withTransaction {
            cardIds.chunked(500).forEach { chunk ->
                cardDao.resetCardsToUnused(chunk)
            }
        }
    }

    suspend fun deleteCardsByIds(cardIds: List<Long>): Result<Unit> = safeDbCall {
        if (cardIds.isEmpty()) return@safeDbCall
        database.withTransaction {
            cardIds.chunked(500).forEach { chunk ->
                cardDao.deleteCardsByIds(chunk)
            }
        }
    }

    fun observeSessionsForUser(username: String): Flow<List<CardSessionEntity>> =
        sessionDao.observeSessionsForUser(username)

    suspend fun getSessionsForUserOnce(username: String): List<CardSessionEntity> =
        sessionDao.getSessionsForUserOnce(username)

    suspend fun replaceSessionsForUser(username: String, sessions: List<CardSessionEntity>): Result<Unit> =
        safeDbCall {
            database.withTransaction {
                sessionDao.deleteSessionsForUser(username)
                if (sessions.isNotEmpty()) {
                    sessionDao.insertSessions(sessions)
                }
            }
        }

    fun observeAllTemplates(): Flow<List<CardTemplateEntity>> = templateDao.observeAllTemplates()
    suspend fun saveTemplate(template: CardTemplateEntity): Result<Long> = safeDbCall { templateDao.upsertTemplate(template) }
    suspend fun deleteTemplate(template: CardTemplateEntity): Result<Unit> = safeDbCall { templateDao.deleteTemplate(template) }

    fun observeAllSalesLogs(): Flow<List<PrintSalesLogEntity>> = salesDao.observeAllSalesLogs()
    fun observeSalesLogsBetween(fromDateKey: String, toDateKey: String): Flow<List<PrintSalesLogEntity>> =
        salesDao.observeSalesLogsBetween(fromDateKey, toDateKey)
    suspend fun recordSalesLog(log: PrintSalesLogEntity): Result<Long> = safeDbCall { salesDao.insertSalesLog(log) }
    suspend fun deleteSalesLog(logId: Long): Result<Unit> = safeDbCall { salesDao.deleteSalesLogById(logId) }

    fun observeComprehensiveSalesReport(): Flow<ComprehensiveSalesReport> =
        salesDao.observeAllSalesLogs().map { logs ->
            val today = DateTimeUtils.todayDateKey()
            val yesterday = DateTimeUtils.yesterdayDateKey()
            val weekStart = DateTimeUtils.daysAgoDateKey(6)
            val monthStart = DateTimeUtils.monthStartDateKey()

            fun summarize(items: List<PrintSalesLogEntity>) = PeriodSalesSummary(
                totalAmount = items.sumOf { it.totalAmount },
                totalCards = items.sumOf { it.cardsCount },
                operationsCount = items.size
            )

            val monthLogs = logs.filter { it.dateKey >= monthStart }
            val topPkg = monthLogs.groupBy { it.packageName }
                .map { (name, items) ->
                    TopSellingPackageStat(
                        packageName = name,
                        totalCards = items.sumOf { it.cardsCount },
                        totalAmount = items.sumOf { it.totalAmount }
                    )
                }
                .maxByOrNull { it.totalCards }

            ComprehensiveSalesReport(
                today = summarize(logs.filter { it.dateKey == today }),
                yesterday = summarize(logs.filter { it.dateKey == yesterday }),
                weekly = summarize(logs.filter { it.dateKey >= weekStart }),
                monthly = summarize(monthLogs),
                allTime = summarize(logs),
                topSellingPackage = topPkg
            )
        }
}
