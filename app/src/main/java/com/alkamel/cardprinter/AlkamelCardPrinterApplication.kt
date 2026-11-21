package com.alkamel.cardprinter

import android.app.Application
import com.alkamel.cardprinter.data.local.AlkamelDatabase
import com.alkamel.cardprinter.data.preferences.AppPreferencesManager
import com.alkamel.cardprinter.data.repository.CardPrinterRepository

class AlkamelCardPrinterApplication : Application() {

    companion object {
        const val DATABASE_NAME = "alkamel_cards_printer.db"
    }

    val database: AlkamelDatabase by lazy { AlkamelDatabase.getInstance(this) }
    val repository: CardPrinterRepository by lazy { CardPrinterRepository(database) }
    val preferencesManager: AppPreferencesManager by lazy { AppPreferencesManager(applicationContext) }
}
