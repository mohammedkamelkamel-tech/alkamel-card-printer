package com.alkamel.cardprinter.ui.sales

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alkamel.cardprinter.data.local.entity.PrintSalesLogEntity
import com.alkamel.cardprinter.data.local.util.DateTimeUtils
import com.alkamel.cardprinter.ui.theme.AlkamelTheme
import java.util.Calendar
import java.util.Locale

private val SalesBadgeAmber = Color(0xFFE5A020)
private val SalesBadgePurple = Color(0xFF7B3FE4)
private val SalesBadgeTeal = Color(0xFF2E8A8E)
private val SalesBadgeCyan = Color(0xFF14889E)
private val SalesBadgeGreen = Color(0xFF4EA87E)

@Composable
fun SalesScreen(
    viewModel: SalesViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val ext = AlkamelTheme.extendedColors
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var filterMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(state.statusMessage, state.errorMessage) {
        val msg = state.errorMessage ?: state.statusMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessages()
        }
    }

    fun openNativeDatePicker(currentValue: String, onPicked: (String) -> Unit) {
        val cal = Calendar.getInstance()
        val parts = currentValue.trim().split("-", "/")
        if (parts.size == 3) {
            val y = parts[0].toIntOrNull()
            val m = parts[1].toIntOrNull()
            val d = parts[2].toIntOrNull()
            if (y != null && m != null && d != null && m in 1..12 && d in 1..31) {
                cal.set(Calendar.YEAR, y)
                cal.set(Calendar.MONTH, m - 1)
                cal.set(Calendar.DAY_OF_MONTH, d)
            }
        }
        DatePickerDialog(
            context,
            { _, year, monthZeroBased, dayOfMonth ->
                val formatted = String.format(
                    Locale.US,
                    "%04d-%02d-%02d",
                    year,
                    monthZeroBased + 1,
                    dayOfMonth
                )
                onPicked(formatted)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = ext.headerNavy
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
                .background(ext.headerNavy)
        ) {
            // Top Dark Navy Header matching the original Sales screen layout
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp)
                    .padding(top = 4.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Compact Top Bar (Back • Title • Refresh)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "رجوع",
                            tint = Color.White
                        )
                    }
                    Text(
                        text = "مبيعات يوزر منجر",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(
                        onClick = viewModel::syncSalesFromMikrotik,
                        enabled = !state.isSyncing,
                        modifier = Modifier.size(36.dp)
                    ) {
                        if (state.isSyncing) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "تحديث مبيعات يوزر منجر",
                                tint = Color.White
                            )
                        }
                    }
                }

                // Header Row 1 (RTL): [Amber Calendar] من تاريخ 2026/11/26  الى تاريخ 2026/11/26  [Purple Search]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Amber calendar badge (tapping jumps quickly to today)
                    SalesCircleBadge(
                        icon = Icons.Rounded.DateRange,
                        backgroundColor = SalesBadgeAmber,
                        size = 32.dp,
                        iconSize = 18.dp,
                        modifier = Modifier.clickable { viewModel.showTodaySales() }
                    )

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                openNativeDatePicker(state.fromDate) { picked ->
                                    viewModel.onFromDateChange(picked)
                                }
                            }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = "من تاريخ",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = state.fromDate.replace('-', '/'),
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                openNativeDatePicker(state.toDate) { picked ->
                                    viewModel.onToDateChange(picked)
                                }
                            }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = "الى تاريخ",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = state.toDate.replace('-', '/'),
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    // Purple Search Circle Button
                    SalesCircleBadge(
                        icon = Icons.Rounded.Search,
                        backgroundColor = SalesBadgePurple,
                        size = 34.dp,
                        iconSize = 19.dp,
                        modifier = Modifier.clickable { viewModel.applyDateRangeSearch() }
                    )
                }

                // Header Row 2 (RTL): [Purple Group] [White Filter Pill "الكل"] [Teal Chart] 43002.00 ريال [Green Pie] 312
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Filter icon + White Pill Dropdown (الكل / VLAN / الباقة / التواريخ)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SalesCircleBadge(
                            icon = Icons.Rounded.Hub,
                            backgroundColor = SalesBadgePurple,
                            size = 32.dp,
                            iconSize = 18.dp,
                            modifier = Modifier.clickable { filterMenuExpanded = true }
                        )

                        Box {
                            Surface(
                                onClick = { filterMenuExpanded = true },
                                shape = RoundedCornerShape(10.dp),
                                color = Color.White
                            ) {
                                Text(
                                    text = state.selectedFilter.label,
                                    color = Color(0xFF101926),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = filterMenuExpanded,
                                onDismissRequest = { filterMenuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "عرض مبيعات اليوم",
                                            fontWeight = if (!state.isShowAllMode && state.fromDate == DateTimeUtils.todayDateKey() && state.toDate == DateTimeUtils.todayDateKey()) {
                                                FontWeight.Bold
                                            } else {
                                                FontWeight.Normal
                                            }
                                        )
                                    },
                                    onClick = {
                                        filterMenuExpanded = false
                                        viewModel.showTodaySales()
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "عرض جميع التواريخ",
                                            fontWeight = if (state.isShowAllMode) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        filterMenuExpanded = false
                                        viewModel.showAllSales()
                                    }
                                )
                                HorizontalDivider()
                                state.availableFilters.forEach { option ->
                                    val isSelected = option.kind == state.selectedFilter.kind &&
                                        option.value == state.selectedFilter.value
                                    val prefix = when (option.kind) {
                                        SalesFilterKind.ALL -> ""
                                        SalesFilterKind.VLAN -> "VLAN • "
                                        SalesFilterKind.PACKAGE -> "باقة • "
                                    }
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "$prefix${option.label}",
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        trailingIcon = if (isSelected) {
                                            {
                                                Icon(
                                                    imageVector = Icons.Rounded.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        } else null,
                                        onClick = {
                                            filterMenuExpanded = false
                                            viewModel.onFilterSelected(option)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Total Sales Amount (Teal Badge + Amount ريال)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SalesCircleBadge(
                            icon = Icons.Rounded.BarChart,
                            backgroundColor = SalesBadgeTeal,
                            size = 32.dp,
                            iconSize = 18.dp
                        )
                        Text(
                            text = "${String.format(Locale.US, "%.2f", state.totalAmount)} ريال",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    // Total Sold Cards Count (Green Pie Badge + Count)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SalesCircleBadge(
                            icon = Icons.Rounded.PieChart,
                            backgroundColor = SalesBadgeGreen,
                            size = 32.dp,
                            iconSize = 18.dp
                        )
                        Text(
                            text = "${state.totalCardsCount}",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                // Subtle scope indicator only when viewing all dates or when operations differ from card count
                if (state.isShowAllMode || state.totalOperationsCount != state.totalCardsCount) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (state.isShowAllMode) "النطاق: جميع التواريخ" else "النطاق: ${state.fromDate.replace('-', '/')} - ${state.toDate.replace('-', '/')}",
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 11.sp
                        )
                        Text(
                            text = "العمليات: ${state.totalOperationsCount}",
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Curved Light Sheet Container with Individual Sale Cards
            Surface(
                shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (state.visibleLogs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "لا توجد مبيعات مسجلة في النطاق المحدد.\nاضغط أيقونة التحديث بالأعلى لمزامنة مبيعات وجلسات يوزر منجر.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(state.visibleLogs, key = { it.id }) { log ->
                            SalesRecordCard(log = log)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SalesRecordCard(log: PrintSalesLogEntity) {
    val dateDisplay = remember(log.dateKey, log.createdAt) {
        val raw = log.dateKey.trim()
        if (raw.isNotBlank()) {
            raw.replace('-', '/')
        } else {
            DateTimeUtils.formatDateKey(log.createdAt).replace('-', '/')
        }
    }
    val packageDisplay = remember(log.packageName, log.cardsCount) {
        val base = log.packageName.trim().ifBlank { "-" }
        if (log.cardsCount > 1) "$base (${log.cardsCount})" else base
    }
    val vlanDisplay = remember(log.connectionVlan) {
        log.connectionVlan.trim().ifBlank { "-" }
    }
    val downloadDisplay = remember(log.downloadMb, log.downloadLabel) {
        if (log.downloadMb > 0.0) {
            log.downloadLabel.trim().ifBlank { "0" }
        } else {
            "0"
        }
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Row 1: Right = Green Card Icon + Username | Left = Amber Wallet Icon + Price
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SalesMetricCell(
                    icon = Icons.Rounded.CreditCard,
                    badgeColor = SalesBadgeGreen,
                    text = log.cardUsername.ifBlank { "-" },
                    isLtrText = log.cardUsername.all { it.isDigit() || it == '-' || it == '_' },
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1.18f)
                )
                SalesMetricCell(
                    icon = Icons.Rounded.AccountBalanceWallet,
                    badgeColor = SalesBadgeAmber,
                    text = String.format(Locale.US, "%.2f", log.totalAmount),
                    isLtrText = true,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(0.82f)
                )
            }

            // Row 2: Right = Purple Calendar Icon + Date | Left = Purple Group Icon + Package
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SalesMetricCell(
                    icon = Icons.Rounded.DateRange,
                    badgeColor = SalesBadgePurple,
                    text = dateDisplay,
                    isLtrText = true,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1.18f)
                )
                SalesMetricCell(
                    icon = Icons.Rounded.Hub,
                    badgeColor = SalesBadgePurple,
                    text = packageDisplay,
                    isLtrText = false,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(0.82f)
                )
            }

            // Row 3: Right = Cyan Wi-Fi Icon + Connected VLAN (physical port strictly fallback) | Left = Teal Chart Icon + Usage
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SalesMetricCell(
                    icon = Icons.Rounded.Wifi,
                    badgeColor = SalesBadgeCyan,
                    text = vlanDisplay,
                    isLtrText = true,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1.18f)
                )
                SalesMetricCell(
                    icon = Icons.Rounded.BarChart,
                    badgeColor = SalesBadgeTeal,
                    text = downloadDisplay,
                    isLtrText = true,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(0.82f)
                )
            }
        }
    }
}

@Composable
private fun SalesMetricCell(
    icon: ImageVector,
    badgeColor: Color,
    text: String,
    isLtrText: Boolean,
    fontWeight: FontWeight,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SalesCircleBadge(
            icon = icon,
            backgroundColor = badgeColor,
            size = 30.dp,
            iconSize = 16.dp
        )
        if (isLtrText) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = text,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = fontWeight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = fontWeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SalesCircleBadge(
    icon: ImageVector,
    backgroundColor: Color,
    size: Dp,
    iconSize: Dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(iconSize)
        )
    }
}
