package com.alkamel.cardprinter.ui.topsales

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Leaderboard
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alkamel.cardprinter.data.local.model.DailyPackageSalesItem
import com.alkamel.cardprinter.data.local.model.DailyTopPackagesGroup
import com.alkamel.cardprinter.data.local.model.UnassignedVlanUsageSummary
import com.alkamel.cardprinter.data.local.model.VlanDataUsageItem
import com.alkamel.cardprinter.ui.theme.AlkamelTheme
import java.util.Calendar
import java.util.Locale

private val RankGold = Color(0xFFF59E0B)
private val RankSilver = Color(0xFF94A3B8)
private val RankBronze = Color(0xFFD97706)
private val UsageHighCoral = Color(0xFFEF4444)
private val UsageMediumAmber = Color(0xFFF59E0B)
private val UsageNormalTeal = Color(0xFF0D9488)
private val UsageLowBlue = Color(0xFF3B82F6)

@Composable
fun TopSalesScreen(
    viewModel: TopSalesViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val ext = AlkamelTheme.extendedColors
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

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
            // Top Dark Navy Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp)
                    .padding(top = 4.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Top Bar (Back • Title • Sync)
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
                        text = "الأكثر مبيعات",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(
                        onClick = viewModel::syncFromMikrotik,
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
                                contentDescription = "تحديث من يوزر منجر",
                                tint = Color.White
                            )
                        }
                    }
                }

                // Segmented Switcher for the 2 Required Types
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.10f))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TopSalesTabButton(
                        title = "الفئات الأكثر مبيعاً يومياً",
                        icon = Icons.Rounded.Leaderboard,
                        selected = state.selectedTab == TopSalesTab.DAILY_TOP_PACKAGES,
                        activeColor = ext.royalActionBlue,
                        onClick = { viewModel.selectTab(TopSalesTab.DAILY_TOP_PACKAGES) },
                        modifier = Modifier.weight(1f)
                    )
                    TopSalesTabButton(
                        title = "الفيلانات الأكثر استهلاكاً",
                        icon = Icons.Rounded.Wifi,
                        selected = state.selectedTab == TopSalesTab.VLAN_DATA_USAGE,
                        activeColor = ext.reportTeal,
                        onClick = { viewModel.selectTab(TopSalesTab.VLAN_DATA_USAGE) },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Quick Period Presets + Custom Date Picker
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PeriodChip(
                        label = "الكل",
                        selected = state.datePreset == TopSalesDatePreset.ALL,
                        onClick = { viewModel.selectPreset(TopSalesDatePreset.ALL) }
                    )
                    PeriodChip(
                        label = "اليوم",
                        selected = state.datePreset == TopSalesDatePreset.TODAY,
                        onClick = { viewModel.selectPreset(TopSalesDatePreset.TODAY) }
                    )
                    PeriodChip(
                        label = "آخر 7 أيام",
                        selected = state.datePreset == TopSalesDatePreset.LAST_7_DAYS,
                        onClick = { viewModel.selectPreset(TopSalesDatePreset.LAST_7_DAYS) }
                    )
                    PeriodChip(
                        label = "هذا الشهر",
                        selected = state.datePreset == TopSalesDatePreset.THIS_MONTH,
                        onClick = { viewModel.selectPreset(TopSalesDatePreset.THIS_MONTH) }
                    )
                    DateRangePill(
                        prefix = "من",
                        dateText = state.fromDate.replace('-', '/'),
                        highlighted = state.datePreset == TopSalesDatePreset.CUSTOM,
                        onClick = {
                            openNativeDatePicker(state.fromDate) { picked ->
                                viewModel.onFromDateChange(picked)
                            }
                        }
                    )
                    DateRangePill(
                        prefix = "إلى",
                        dateText = state.toDate.replace('-', '/'),
                        highlighted = state.datePreset == TopSalesDatePreset.CUSTOM,
                        onClick = {
                            openNativeDatePicker(state.toDate) { picked ->
                                viewModel.onToDateChange(picked)
                            }
                        }
                    )
                }

                // Compact KPI Summary Row for Active Tab
                if (state.selectedTab == TopSalesTab.DAILY_TOP_PACKAGES) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HeaderKpiBox(
                            label = "الأيام",
                            value = "${state.dailyTopPackages.size}",
                            accent = RankGold,
                            modifier = Modifier.weight(0.8f)
                        )
                        HeaderKpiBox(
                            label = "الكروت المباعة",
                            value = "${state.totalDailyCards}",
                            accent = ext.voucherGreenAccent,
                            modifier = Modifier.weight(1f)
                        )
                        HeaderKpiBox(
                            label = "إجمالي المبيعات",
                            value = "${formatMoneyCompact(state.totalDailyAmount)} ريال",
                            accent = ext.royalActionBlue,
                            modifier = Modifier.weight(1.25f)
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HeaderKpiBox(
                            label = "الفيلانات",
                            value = "${state.vlanRanking.items.size}",
                            accent = ext.reportTeal,
                            modifier = Modifier.weight(0.85f)
                        )
                        HeaderKpiBox(
                            label = "الاستهلاك الكلي",
                            value = state.vlanRanking.totalVlanDownloadLabel,
                            accent = RankGold,
                            isLtrValue = true,
                            modifier = Modifier.weight(1.2f)
                        )
                        HeaderKpiBox(
                            label = "الكروت / الجلسات",
                            value = "${state.vlanRanking.totalVlanCards}",
                            accent = ext.royalActionBlue,
                            modifier = Modifier.weight(0.95f)
                        )
                    }
                }
            }

            // Main Curved Content Sheet
            Surface(
                shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (state.selectedTab) {
                    TopSalesTab.DAILY_TOP_PACKAGES -> {
                        if (state.dailyTopPackages.isEmpty()) {
                            EmptyAnalyticsState(
                                message = "لا توجد مبيعات مسجلة للفئات في النطاق المحدد.\nاضغط أيقونة التحديث بالأعلى لمزامنة مبيعات يوزر منجر."
                            )
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(state.dailyTopPackages, key = { it.dateKey }) { dayGroup ->
                                    DailyTopPackagesCard(group = dayGroup)
                                }
                            }
                        }
                    }

                    TopSalesTab.VLAN_DATA_USAGE -> {
                        val vlanItems = state.vlanRanking.items
                        val unassigned = state.vlanRanking.unassignedSummary
                        if (vlanItems.isEmpty() && unassigned == null) {
                            EmptyAnalyticsState(
                                message = "لا توجد بيانات استهلاك للفيلانات في النطاق المحدد.\nاضغط أيقونة التحديث بالأعلى لجلب جلسات وفيلانات يوزر منجر."
                            )
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                if (vlanItems.isEmpty()) {
                                    item(key = "no_named_vlans") {
                                        Card(
                                            shape = RoundedCornerShape(18.dp),
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = "لم يتم رصد أسماء VLANs مرتبطة بالجلسات في هذا النطاق بعد. اضغط التحديث لمزامنة جدول الـ VLAN من المايكروتيك.",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(18.dp)
                                            )
                                        }
                                    }
                                } else {
                                    items(vlanItems, key = { "${it.rank}_${it.vlanName}" }) { item ->
                                        VlanDataUsageRankCard(
                                            item = item,
                                            totalVlanCount = vlanItems.size
                                        )
                                    }
                                }

                                if (unassigned != null) {
                                    item(key = "unassigned_vlan_summary") {
                                        UnassignedVlanCard(summary = unassigned)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyTopPackagesCard(group: DailyTopPackagesGroup) {
    val ext = AlkamelTheme.extendedColors
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Day Header Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(ext.royalActionBlue.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(ext.royalActionBlue),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DateRange,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = group.displayDate,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = "الأكثر مبيعاً: ${group.topPackageName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = ext.royalActionBlue,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${formatMoneyCompact(group.totalAmount)} ريال",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = ext.voucherGreenAccent
                    )
                    Text(
                        text = "${group.totalCards} كرت • ${group.packages.size} فئة",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Ranked Categories/Packages from Highest to Lowest for This Day
            group.packages.forEachIndexed { idx, pkg ->
                DailyPackageRow(item = pkg)
                if (idx < group.packages.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        thickness = 0.8.dp
                    )
                }
            }
        }
    }
}

@Composable
private fun DailyPackageRow(item: DailyPackageSalesItem) {
    val ext = AlkamelTheme.extendedColors
    val badgeColor = when (item.rank) {
        1 -> RankGold
        2 -> RankSilver
        3 -> RankBronze
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val badgeTextColor = if (item.rank <= 3) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    val barColor = when (item.rank) {
        1 -> RankGold
        2 -> ext.royalActionBlue
        3 -> ext.reportTeal
        else -> ext.reportPurpleAccent
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(badgeColor),
                contentAlignment = Alignment.Center
            ) {
                if (item.rank == 1) {
                    Icon(
                        imageVector = Icons.Rounded.EmojiEvents,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                } else {
                    Text(
                        text = "${item.rank}",
                        color = badgeTextColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Hub,
                        contentDescription = null,
                        tint = barColor,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = item.packageName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "${item.cardsCount} كرت مباع • نسبة ${item.shareOfDayPercent}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${formatMoneyCompact(item.totalAmount)} ريال",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "#${item.rank} في اليوم",
                    style = MaterialTheme.typography.labelSmall,
                    color = barColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        LinearProgressIndicator(
            progress = { item.shareOfDayFraction.coerceIn(0.04f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(6.dp)),
            color = barColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            strokeCap = StrokeCap.Round
        )
    }
}

@Composable
private fun VlanDataUsageRankCard(
    item: VlanDataUsageItem,
    totalVlanCount: Int
) {
    val tierColor = when {
        item.rank == 1 -> UsageHighCoral
        item.rank == 2 -> UsageMediumAmber
        item.rank == 3 -> RankGold
        item.rank <= (totalVlanCount / 2).coerceAtLeast(4) -> UsageNormalTeal
        else -> UsageLowBlue
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
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Progressive Rank Circle (#1 highest -> #N lowest)
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(tierColor.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "#${item.rank}",
                        color = tierColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                // VLAN Name + Share
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Wifi,
                            contentDescription = null,
                            tint = tierColor,
                            modifier = Modifier.size(17.dp)
                        )
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = item.vlanName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Text(
                        text = "نسبة الاستهلاك: ${item.shareOfTotalPercent}% من إجمالي الفيلانات",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Total Data Consumption Badge
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = tierColor.copy(alpha = 0.15f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DataUsage,
                            contentDescription = null,
                            tint = tierColor,
                            modifier = Modifier.size(16.dp)
                        )
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = item.totalDownloadLabel,
                                color = tierColor,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }

            // Progressive Consumption Bar (scales smoothly from 100% at #1 down to lowest)
            LinearProgressIndicator(
                progress = { item.shareOfMaxFraction.coerceIn(0.04f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(9.dp)
                    .clip(RoundedCornerShape(8.dp)),
                color = tierColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                strokeCap = StrokeCap.Round
            )

            // Bottom Details Row: Cards Count • Sessions/Operations • Sales Amount
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CreditCard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "${item.cardsCount} كرت • ${item.sessionsOrOpsCount} جلسة/عملية",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AccountBalanceWallet,
                        contentDescription = null,
                        tint = RankGold,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "${formatMoneyCompact(item.totalSalesAmount)} ريال",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun UnassignedVlanCard(summary: UnassignedVlanUsageSummary) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "سجلات بدون VLAN محدد",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${summary.cardsCount} كرت • ${summary.sessionsOrOpsCount} عملية",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = summary.totalDownloadLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TopSalesTabButton(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(11.dp),
        color = if (selected) activeColor else Color.Transparent,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) Color.White else Color.White.copy(alpha = 0.72f),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = title,
                color = if (selected) Color.White else Color.White.copy(alpha = 0.78f),
                fontSize = 11.5.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PeriodChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) Color.White else Color.White.copy(alpha = 0.12f)
    ) {
        Text(
            text = label,
            color = if (selected) Color(0xFF0F172A) else Color.White,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun DateRangePill(
    prefix: String,
    dateText: String,
    highlighted: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (highlighted) RankGold.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.10f),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = prefix,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = dateText,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun HeaderKpiBox(
    label: String,
    value: String,
    accent: Color,
    isLtrValue: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White.copy(alpha = 0.09f),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (isLtrValue) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        text = value,
                        color = accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Text(
                    text = value,
                    color = accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun EmptyAnalyticsState(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

private fun formatMoneyCompact(amount: Double): String {
    return if (amount % 1.0 == 0.0) {
        amount.toLong().toString()
    } else {
        String.format(Locale.US, "%.2f", amount)
    }
}
