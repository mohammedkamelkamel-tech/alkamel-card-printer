package com.alkamel.cardprinter.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.alkamel.cardprinter.data.local.entity.CardEntity
import com.alkamel.cardprinter.data.local.model.CardCountsSummary
import com.alkamel.cardprinter.data.local.model.CardStatus
import com.alkamel.cardprinter.data.local.model.CardSyncMeta
import com.alkamel.cardprinter.data.local.model.PackageCardCount
import kotlinx.coroutines.flow.Flow

@Dao
interface CardDao {

    @Query("SELECT * FROM cards ORDER BY createdAt DESC")
    fun observeAllCards(): Flow<List<CardEntity>>

    @Query("SELECT * FROM cards")
    suspend fun getAllCardsOnce(): List<CardEntity>

    @Query("SELECT id, username, price, isPrinted, printedAt, printedDateKey, createdAt, mikrotikId, sourceFileName FROM cards")
    suspend fun getAllCardSyncMetaOnce(): List<CardSyncMeta>

    @Query("SELECT username FROM cards")
    suspend fun getAllUsernamesOnce(): List<String>

    @Query("SELECT * FROM cards WHERE status = :status ORDER BY createdAt DESC")
    fun observeCardsByStatus(status: CardStatus): Flow<List<CardEntity>>

    @Query("SELECT * FROM cards WHERE status = 'EXPIRED' ORDER BY createdAt DESC")
    fun observeExpiredCards(): Flow<List<CardEntity>>

    @Query("SELECT COUNT(*) FROM cards")
    fun observeTotalCount(): Flow<Int>

    @Query(
        """
        SELECT
            COUNT(*) AS totalCount,
            COALESCE(SUM(CASE WHEN status = 'UNUSED' THEN 1 ELSE 0 END), 0) AS unusedCount,
            COALESCE(SUM(CASE WHEN status = 'USED' THEN 1 ELSE 0 END), 0) AS usedCount,
            COALESCE(SUM(CASE WHEN status = 'EXPIRED' THEN 1 ELSE 0 END), 0) AS expiredCount,
            COALESCE(SUM(dataUsageMb), 0.0) AS totalDataUsageMb
        FROM cards
        """
    )
    fun observeCardCounts(): Flow<CardCountsSummary>

    @Query(
        """
        SELECT packageName, COUNT(*) AS cardsCount
        FROM cards
        WHERE packageName != ''
        GROUP BY packageName
        """
    )
    fun observePackageCardCounts(): Flow<List<PackageCardCount>>

    @Query("UPDATE cards SET price = :price WHERE LOWER(packageName) = LOWER(:packageName)")
    suspend fun updatePriceForPackage(packageName: String, price: Double)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCardsIgnore(cards: List<CardEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCards(cards: List<CardEntity>): List<Long>

    @Update
    suspend fun updateCard(card: CardEntity)

    @Query("UPDATE cards SET isPrinted = 1, printedAt = :printedAt, printedDateKey = :dateKey WHERE id IN (:cardIds)")
    suspend fun markCardsPrinted(cardIds: List<Long>, printedAt: Long, dateKey: String)

    @Query("UPDATE cards SET status = 'UNUSED', isPrinted = 0, printedAt = NULL, printedDateKey = NULL WHERE id IN (:cardIds)")
    suspend fun resetCardsToUnused(cardIds: List<Long>)

    @Query("UPDATE cards SET status = 'EXPIRED' WHERE id IN (:cardIds)")
    suspend fun markCardsExpired(cardIds: List<Long>)

    @Query(
        """
        UPDATE cards
        SET status = 'EXPIRED'
        WHERE LOWER(packageName) = LOWER(:packageName)
          AND status != 'EXPIRED'
          AND (
              (:dataLimitMb > 0.0 AND dataUsageMb >= (:dataLimitMb * 0.99))
              OR (:uptimeLimitMinutes > 0 AND uptimeMinutes >= :uptimeLimitMinutes)
          )
        """
    )
    suspend fun markExpiredCardsForPackageLimits(
        packageName: String,
        dataLimitMb: Double,
        uptimeLimitMinutes: Long
    ): Int

    @Query("DELETE FROM cards WHERE id IN (:cardIds)")
    suspend fun deleteCardsByIds(cardIds: List<Long>)
}
