package com.alkamel.cardprinter.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.alkamel.cardprinter.AlkamelCardPrinterApplication
import com.alkamel.cardprinter.data.preferences.AppSettingsState
import com.alkamel.cardprinter.ui.cards.UserManagerCardsScreen
import com.alkamel.cardprinter.ui.cards.UserManagerCardsViewModel
import com.alkamel.cardprinter.ui.dashboard.DashboardScreen
import com.alkamel.cardprinter.ui.dashboard.DashboardViewModel
import com.alkamel.cardprinter.ui.designer.CardDesignerScreen
import com.alkamel.cardprinter.ui.designer.CardDesignerViewModel
import com.alkamel.cardprinter.ui.expired.ExpiredCardsScreen
import com.alkamel.cardprinter.ui.expired.ExpiredCardsViewModel
import com.alkamel.cardprinter.ui.generation.CardGenerationScreen
import com.alkamel.cardprinter.ui.generation.CardGenerationViewModel
import com.alkamel.cardprinter.ui.login.LoginScreen
import com.alkamel.cardprinter.ui.login.LoginViewModel
import com.alkamel.cardprinter.ui.packages.PackagesScreen
import com.alkamel.cardprinter.ui.packages.PackagesViewModel
import com.alkamel.cardprinter.ui.printpreview.PrintPreviewScreen
import com.alkamel.cardprinter.ui.printpreview.PrintPreviewViewModel
import com.alkamel.cardprinter.ui.reports.ReportsScreen
import com.alkamel.cardprinter.ui.reports.ReportsViewModel
import com.alkamel.cardprinter.ui.sales.SalesScreen
import com.alkamel.cardprinter.ui.sales.SalesViewModel
import com.alkamel.cardprinter.ui.settings.AppLockScreen
import com.alkamel.cardprinter.ui.settings.AppSettingsScreen
import com.alkamel.cardprinter.ui.settings.AppSettingsViewModel
import com.alkamel.cardprinter.ui.splash.SplashScreen

@Composable
fun AppNavGraph() {
    val context = LocalContext.current
    val app = context.applicationContext as AlkamelCardPrinterApplication
    val factory = remember(app) { AlkamelViewModelFactory(app) }
    val navController = rememberNavController()

    val settings by app.preferencesManager.settingsFlow.collectAsState(initial = AppSettingsState())
    var isUnlocked by rememberSaveable { mutableStateOf(false) }

    if (settings.appLockEnabled && !isUnlocked) {
        AppLockScreen(
            preferencesManager = app.preferencesManager,
            biometricEnabled = settings.biometricEnabled,
            onUnlocked = { isUnlocked = true }
        )
        return
    }

    NavHost(
        navController = navController,
        startDestination = AppRoutes.SPLASH
    ) {
        composable(AppRoutes.SPLASH) {
            SplashScreen(
                onSplashFinished = {
                    navController.navigate(AppRoutes.LOGIN) {
                        popUpTo(AppRoutes.SPLASH) { inclusive = true }
                    }
                }
            )
        }

        composable(AppRoutes.LOGIN) {
            val vm: LoginViewModel = viewModel(factory = factory)
            LoginScreen(
                viewModel = vm,
                onLoginSuccess = {
                    navController.navigate(AppRoutes.DASHBOARD) {
                        popUpTo(AppRoutes.LOGIN) { inclusive = true }
                    }
                }
            )
        }

        composable(AppRoutes.DASHBOARD) {
            val vm: DashboardViewModel = viewModel(factory = factory)
            DashboardScreen(
                viewModel = vm,
                onNavigate = { route -> navController.navigate(route) }
            )
        }

        composable(AppRoutes.USER_MANAGER_CARDS) {
            val vm: UserManagerCardsViewModel = viewModel(factory = factory)
            UserManagerCardsScreen(
                viewModel = vm,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToPrintPreview = { navController.navigate(AppRoutes.PRINT_PREVIEW) }
            )
        }

        composable(AppRoutes.CARD_GENERATION) {
            val vm: CardGenerationViewModel = viewModel(factory = factory)
            CardGenerationScreen(
                viewModel = vm,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToDesigner = { navController.navigate(AppRoutes.CARD_DESIGNER) },
                onNavigateToPrintPreview = { navController.navigate(AppRoutes.PRINT_PREVIEW) }
            )
        }

        composable(AppRoutes.CARD_DESIGNER) {
            val vm: CardDesignerViewModel = viewModel(factory = factory)
            CardDesignerScreen(
                viewModel = vm,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(AppRoutes.SALES) {
            val vm: SalesViewModel = viewModel(factory = factory)
            SalesScreen(
                viewModel = vm,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(AppRoutes.REPORTS) {
            val vm: ReportsViewModel = viewModel(factory = factory)
            ReportsScreen(
                viewModel = vm,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(AppRoutes.EXPIRED_CARDS) {
            val vm: ExpiredCardsViewModel = viewModel(factory = factory)
            ExpiredCardsScreen(
                viewModel = vm,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(AppRoutes.PACKAGES) {
            val vm: PackagesViewModel = viewModel(factory = factory)
            PackagesScreen(
                viewModel = vm,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(AppRoutes.PRINT_PREVIEW) {
            val vm: PrintPreviewViewModel = viewModel(factory = factory)
            PrintPreviewScreen(
                viewModel = vm,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(AppRoutes.SETTINGS) {
            val vm: AppSettingsViewModel = viewModel(factory = factory)
            AppSettingsScreen(
                viewModel = vm,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
