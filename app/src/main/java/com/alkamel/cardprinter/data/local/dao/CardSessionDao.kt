package com.alkamel.cardprinter.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.alkamel.cardprinter.data.local.entity.CardSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CardSessionDao {

    @Query("SELECT * FROM card_sessions WHERE username = :username ORDER BY isActive DESC, id DESC")
    fun observeSessionsForUser(username: String): Flow<List<CardSessionEntity>>

    @Query("SELECT * FROM card_sessions WHERE username = :username ORDER BY isActive DESC, id DESC")
    suspend fun getSessionsForUserOnce(username: String): List<CardSessionEntity>

    @Query("DELETE FROM card_sessions WHERE username = :username")
    suspend fun deleteSessionsForUser(username: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSessions(sessions: List<CardSessionEntity>)
}
