package com.alkamel.cardprinter.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.alkamel.cardprinter.AlkamelCardPrinterApplication
import com.alkamel.cardprinter.ui.cards.UserManagerCardsViewModel
import com.alkamel.cardprinter.ui.dashboard.DashboardViewModel
import com.alkamel.cardprinter.ui.designer.CardDesignerViewModel
import com.alkamel.cardprinter.ui.expired.ExpiredCardsViewModel
import com.alkamel.cardprinter.ui.generation.CardGenerationViewModel
import com.alkamel.cardprinter.ui.login.LoginViewModel
import com.alkamel.cardprinter.ui.packages.PackagesViewModel
import com.alkamel.cardprinter.ui.printpreview.PrintPreviewViewModel
import com.alkamel.cardprinter.ui.reports.ReportsViewModel
import com.alkamel.cardprinter.ui.sales.SalesViewModel
import com.alkamel.cardprinter.ui.settings.AppSettingsViewModel
import com.alkamel.cardprinter.ui.topsales.TopSalesViewModel

class AlkamelViewModelFactory(
    private val app: AlkamelCardPrinterApplication
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(LoginViewModel::class.java) ->
                LoginViewModel(app.repository) as T
            modelClass.isAssignableFrom(DashboardViewModel::class.java) ->
                DashboardViewModel(app.repository) as T
            modelClass.isAssignableFrom(UserManagerCardsViewModel::class.java) ->
                UserManagerCardsViewModel(app.repository) as T
            modelClass.isAssignableFrom(CardGenerationViewModel::class.java) ->
                CardGenerationViewModel(app.repository) as T
            modelClass.isAssignableFrom(CardDesignerViewModel::class.java) ->
                CardDesignerViewModel(app.repository) as T
            modelClass.isAssignableFrom(SalesViewModel::class.java) ->
                SalesViewModel(app.repository) as T
            modelClass.isAssignableFrom(TopSalesViewModel::class.java) ->
                TopSalesViewModel(app.repository) as T
            modelClass.isAssignableFrom(ReportsViewModel::class.java) ->
                ReportsViewModel(app.repository) as T
            modelClass.isAssignableFrom(ExpiredCardsViewModel::class.java) ->
                ExpiredCardsViewModel(app.repository) as T
            modelClass.isAssignableFrom(PackagesViewModel::class.java) ->
                PackagesViewModel(app.repository) as T
            modelClass.isAssignableFrom(PrintPreviewViewModel::class.java) ->
                PrintPreviewViewModel(app.repository, app.preferencesManager) as T
            modelClass.isAssignableFrom(AppSettingsViewModel::class.java) ->
                AppSettingsViewModel(app.preferencesManager) as T
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
