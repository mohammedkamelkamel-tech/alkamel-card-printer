package com.alkamel.cardprinter.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.alkamel.cardprinter.data.local.entity.CardEntity
import com.alkamel.cardprinter.data.local.model.CardStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface CardDao {

    @Query("SELECT * FROM cards ORDER BY createdAt DESC")
    fun observeAllCards(): Flow<List<CardEntity>>

    @Query("SELECT * FROM cards")
    suspend fun getAllCardsOnce(): List<CardEntity>

    @Query("SELECT * FROM cards WHERE status = :status ORDER BY createdAt DESC")
    fun observeCardsByStatus(status: CardStatus): Flow<List<CardEntity>>

    @Query("SELECT COUNT(*) FROM cards")
    fun observeTotalCount(): Flow<Int>

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

    @Query("DELETE FROM cards WHERE id IN (:cardIds)")
    suspend fun deleteCardsByIds(cardIds: List<Long>)
}
