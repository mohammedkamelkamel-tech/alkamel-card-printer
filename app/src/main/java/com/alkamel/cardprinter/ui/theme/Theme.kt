package com.alkamel.cardprinter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

@Immutable
data class AlkamelExtendedColors(
    val headerNavy: Color,
    val royalActionBlue: Color,
    val voucherGreen: Color,
    val voucherGreenAccent: Color,
    val voucherRose: Color,
    val expiredBurgundy: Color,
    val expiredCoralBadge: Color,
    val reportPurple: Color,
    val reportPurpleAccent: Color,
    val reportBlue: Color,
    val reportBlueAccent: Color,
    val reportTeal: Color,
    val reportAmberGold: Color,
    val reportTerracotta: Color,
    val destructivePink: Color,
    val actionCoral: Color,
    val inputContainer: Color,
    val inputText: Color,
    val inputPlaceholder: Color,
    val inputBorder: Color,
    val dropdownContainer: Color,
    val dropdownText: Color,
    val savedNetworkCard: Color
)

private val LightExtendedColors = AlkamelExtendedColors(
    headerNavy = HeaderNavy,
    royalActionBlue = RoyalActionBlue,
    voucherGreen = VoucherGreen,
    voucherGreenAccent = VoucherGreenAccent,
    voucherRose = VoucherRose,
    expiredBurgundy = ExpiredBurgundy,
    expiredCoralBadge = ExpiredCoralBadge,
    reportPurple = ReportPurple,
    reportPurpleAccent = ReportPurpleAccent,
    reportBlue = ReportBlue,
    reportBlueAccent = ReportBlueAccent,
    reportTeal = ReportTeal,
    reportAmberGold = ReportAmberGold,
    reportTerracotta = ReportTerracotta,
    destructivePink = DestructivePink,
    actionCoral = ActionCoral,
    inputContainer = LightInputContainer,
    inputText = LightOnSurface,
    inputPlaceholder = LightOnSurfaceVariant,
    inputBorder = LightInputBorder,
    dropdownContainer = LightInputContainer,
    dropdownText = LightOnSurface,
    savedNetworkCard = Color(0xFFEFF6FF)
)

private val DarkExtendedColors = AlkamelExtendedColors(
    headerNavy = Color(0xFF0A1220),
    royalActionBlue = Color(0xFF2196F3),
    voucherGreen = Color(0xFF2E7D32),
    voucherGreenAccent = Color(0xFF4CAF50),
    voucherRose = Color(0xFFB73360),
    expiredBurgundy = Color(0xFF880E4F),
    expiredCoralBadge = Color(0xFFD8436A),
    reportPurple = Color(0xFF7B1FA2),
    reportPurpleAccent = Color(0xFFAB47BC),
    reportBlue = Color(0xFF1976D2),
    reportBlueAccent = Color(0xFF42A5F5),
    reportTeal = Color(0xFF00897B),
    reportAmberGold = Color(0xFFFB8C00),
    reportTerracotta = Color(0xFFD84315),
    destructivePink = Color(0xFFD83B68),
    actionCoral = Color(0xFFEF5350),
    inputContainer = DarkInputContainer,
    inputText = DarkOnSurface,
    inputPlaceholder = DarkOnSurfaceVariant,
    inputBorder = DarkInputBorder,
    dropdownContainer = DarkInputContainer,
    dropdownText = DarkOnSurface,
    savedNetworkCard = Color(0xFF1B2B45)
)

private val LightColorScheme = lightColorScheme(
    primary = RoyalActionBlue,
    onPrimary = Color.White,
    secondary = ReportTeal,
    onSecondary = Color.White,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = LightOnSurfaceVariant,
    error = DestructivePink,
    onError = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF42A5F5),
    onPrimary = Color(0xFF061326),
    secondary = Color(0xFF26A69A),
    onSecondary = Color(0xFF04201E),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = Color(0xFF24334A),
    onSurfaceVariant = DarkOnSurfaceVariant,
    error = Color(0xFFF06292),
    onError = Color(0xFF2B0714)
)

private val LocalAlkamelExtendedColors = staticCompositionLocalOf { LightExtendedColors }

object AlkamelTheme {
    val extendedColors: AlkamelExtendedColors
        @Composable
        get() = LocalAlkamelExtendedColors.current
}

@Composable
fun AlkamelTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl,
        LocalAlkamelExtendedColors provides extendedColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AlkamelTypography,
            shapes = AlkamelShapes,
            content = content
        )
    }
}
