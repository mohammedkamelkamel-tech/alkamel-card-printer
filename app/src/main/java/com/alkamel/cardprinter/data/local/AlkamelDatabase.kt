package com.alkamel.cardprinter.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.alkamel.cardprinter.AlkamelCardPrinterApplication
import com.alkamel.cardprinter.data.local.converter.AlkamelTypeConverters
import com.alkamel.cardprinter.data.local.dao.CardDao
import com.alkamel.cardprinter.data.local.dao.CardPackageDao
import com.alkamel.cardprinter.data.local.dao.CardTemplateDao
import com.alkamel.cardprinter.data.local.dao.PrintSalesLogDao
import com.alkamel.cardprinter.data.local.dao.SavedNetworkDao
import com.alkamel.cardprinter.data.local.entity.CardEntity
import com.alkamel.cardprinter.data.local.entity.CardPackageEntity
import com.alkamel.cardprinter.data.local.entity.CardTemplateEntity
import com.alkamel.cardprinter.data.local.entity.PrintSalesLogEntity
import com.alkamel.cardprinter.data.local.entity.SavedNetworkEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Database(
    entities = [
        SavedNetworkEntity::class,
        CardPackageEntity::class,
        CardEntity::class,
        CardTemplateEntity::class,
        PrintSalesLogEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(AlkamelTypeConverters::class)
abstract class AlkamelDatabase : RoomDatabase() {

    abstract fun savedNetworkDao(): SavedNetworkDao
    abstract fun cardPackageDao(): CardPackageDao
    abstract fun cardDao(): CardDao
    abstract fun cardTemplateDao(): CardTemplateDao
    abstract fun printSalesLogDao(): PrintSalesLogDao

    companion object {
        @Volatile
        private var INSTANCE: AlkamelDatabase? = null
        private val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun getInstance(context: Context): AlkamelDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also { db ->
                    INSTANCE = db
                    seedScope.launch { DatabaseSeeder.seedIfEmpty(db) }
                }
            }
        }

        private fun buildDatabase(appContext: Context): AlkamelDatabase {
            return Room.databaseBuilder(
                appContext,
                AlkamelDatabase::class.java,
                AlkamelCardPrinterApplication.DATABASE_NAME
            )
                .fallbackToDestructiveMigration()
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(
                    object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            seedScope.launch {
                                INSTANCE?.let { DatabaseSeeder.seedIfEmpty(it) }
                            }
                        }
                    }
                )
                .build()
        }
    }
}
