package com.alkamel.cardprinter.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.alkamel.cardprinter.data.local.entity.CardTemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CardTemplateDao {

    @Query("SELECT * FROM card_templates ORDER BY isDefault DESC, updatedAt DESC")
    fun observeAllTemplates(): Flow<List<CardTemplateEntity>>

    @Query("SELECT COUNT(*) FROM card_templates")
    suspend fun getCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTemplate(template: CardTemplateEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTemplatesIgnore(templates: List<CardTemplateEntity>)

    @Delete
    suspend fun deleteTemplate(template: CardTemplateEntity)
}
