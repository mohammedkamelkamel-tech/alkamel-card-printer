package com.alkamel.cardprinter.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MonetizationOn
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.alkamel.cardprinter.ui.navigation.AppRoutes
import com.alkamel.cardprinter.ui.theme.AlkamelTheme
import com.alkamel.cardprinter.ui.theme.SkyFabBlue

private data class DashboardTile(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val accentColor: Color,
    val route: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onNavigate: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val ext = AlkamelTheme.extendedColors
    var showQuickActionsSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Start live MikroTik telemetry polling while Dashboard is active, stop cleanly on leave
    DisposableEffect(Unit) {
        viewModel.startLivePolling()
        onDispose {
            viewModel.stopLivePolling()
        }
    }

    LaunchedEffect(state.syncCompletionMessage) {
        val msg = state.syncCompletionMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSyncCompletionMessage()
        }
    }

    val tiles = remember(state.totalCardsCount, state.expiredCardsCount, ext) {
        listOf(
            DashboardTile(
                title = "طباعة كروت",
                subtitle = "توليد وإضافة وطباعة A4",
                icon = Icons.Rounded.Print,
                accentColor = ext.royalActionBlue,
                route = AppRoutes.CARD_GENERATION
            ),
            DashboardTile(
                title = "كروت يوزر مانجر",
                subtitle = "${state.totalCardsCount} كرت محفوظ",
                icon = Icons.Rounded.CreditCard,
                accentColor = ext.voucherGreen,
                route = AppRoutes.USER_MANAGER_CARDS
            ),
            DashboardTile(
                title = "الباقات",
                subtitle = "باقات المايكروتيك والأسعار",
                icon = Icons.Rounded.Folder,
                accentColor = ext.reportAmberGold,
                route = AppRoutes.PACKAGES
            ),
            DashboardTile(
                title = "المبيعات",
                subtitle = "سجل العمليات والفواتير",
                icon = Icons.Rounded.MonetizationOn,
                accentColor = ext.reportTeal,
                route = AppRoutes.SALES
            ),
            DashboardTile(
                title = "الكروت المنتهية",
                subtitle = "${state.expiredCardsCount} كرت منتهي",
                icon = Icons.Rounded.PersonOff,
                accentColor = ext.expiredBurgundy,
                route = AppRoutes.EXPIRED_CARDS
            ),
            DashboardTile(
                title = "التقارير",
                subtitle = "إحصائيات فعلية وتصدير PDF",
                icon = Icons.Rounded.Assessment,
                accentColor = ext.reportPurple,
                route = AppRoutes.REPORTS
            ),
            DashboardTile(
                title = "تصميم الكرت",
                subtitle = "القوالب والخلفيات وQR",
                icon = Icons.Rounded.Palette,
                accentColor = ext.reportBlue,
                route = AppRoutes.CARD_DESIGNER
            ),
            DashboardTile(
                title = "الإعدادات",
                subtitle = "طباعة A4 وقفل التطبيق",
                icon = Icons.Rounded.Settings,
                accentColor = ext.reportTerracotta,
                route = AppRoutes.SETTINGS
            )
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showQuickActionsSheet = true },
                containerColor = SkyFabBlue,
                contentColor = Color.White,
                shape = CircleShape
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "إجراءات سريعة",
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        bottomBar = {
            // Progress bar appears ONLY during active sync and auto-hides completely once 100% finishes
            AnimatedVisibility(
                visible = state.isSyncBarVisible,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                Surface(
                    tonalElevation = 4.dp,
                    shadowElevation = 6.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = state.sessionLoadLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = ext.royalActionBlue,
                            modifier = Modifier.weight(1f)
                        )
                        LinearProgressIndicator(
                            progress = { (state.sessionLoadProgress / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .width(92.dp)
                                .height(8.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            color = ext.royalActionBlue,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            strokeCap = StrokeCap.Round
                        )
                        Text(
                            text = "${state.sessionLoadProgress}%",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = ext.royalActionBlue
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Top Dark Header showing LIVE REAL MikroTik Telemetry ("--" when disconnected)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = ext.headerNavy,
                        shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetricGaugeCircle(
                    label = "المعالج",
                    valueText = state.cpuPercent?.let { "$it%" } ?: "--",
                    waveFillFraction = state.cpuPercent?.let { it / 100f } ?: 0f,
                    isLive = state.isConnectedToMikrotik
                )
                MetricGaugeCircle(
                    label = "المتصلين",
                    valueText = state.activeConnectionsCount?.toString() ?: "--",
                    waveFillFraction = 0f,
                    isLive = state.isConnectedToMikrotik
                )
                MetricGaugeCircle(
                    label = "مدة التشغيل",
                    valueText = state.uptimeDisplay ?: "--",
                    waveFillFraction = 0f,
                    isLive = state.isConnectedToMikrotik
                )
                MetricGaugeCircle(
                    label = "التحميل",
                    valueText = state.todayDownloadDisplay ?: "--",
                    waveFillFraction = 0f,
                    isLive = state.isConnectedToMikrotik
                )
            }

            // "يوزر مانجر" Action Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { onNavigate(AppRoutes.USER_MANAGER_CARDS) },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ext.royalActionBlue,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "يوزر مانجر",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "${state.networkName}${if (state.activeUser.isNotBlank()) " • ${state.activeUser}" else ""}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Dashboard Tiles Grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 84.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(tiles) { tile ->
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(142.dp)
                            .clickable { onNavigate(tile.route) }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(58.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(tile.accentColor.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = tile.icon,
                                    contentDescription = tile.title,
                                    tint = tile.accentColor,
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = tile.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = tile.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }

    // Real Fast Session Sync Prompt (only when connected to MikroTik)
    if (state.showFastSessionPrompt) {
        Dialog(onDismissRequest = viewModel::dismissFastSessionPrompt) {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2233)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.HelpOutline,
                            contentDescription = null,
                            tint = Color(0xFFF59E0B),
                            modifier = Modifier.size(48.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                    Text(
                        text = "هل تريد جلب الباقات والكروت والجلسات من المايكروتيك الآن؟",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(22.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Button(
                            onClick = viewModel::startFastSessionLoad,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1E88E5),
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                        ) {
                            Text("نعم", fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = viewModel::dismissFastSessionPrompt,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFD81B60),
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                        ) {
                            Text("لا", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showQuickActionsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showQuickActionsSheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    text = "إجراءات سريعة",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
                ListItem(
                    headlineContent = { Text("مزامنة فورية مع المايكروتيك", fontWeight = FontWeight.Bold) },
                    supportingContent = { Text("تحديث حالة المعالج والمتصلين والباقات والكروت الحقيقية") },
                    leadingContent = { Icon(Icons.Rounded.Refresh, contentDescription = null, tint = ext.voucherGreen) },
                    modifier = Modifier.clickable {
                        showQuickActionsSheet = false
                        viewModel.refreshMikrotikNow()
                    }
                )
                ListItem(
                    headlineContent = { Text("توليد وطباعة كروت جديدة (A4)", fontWeight = FontWeight.Bold) },
                    supportingContent = { Text("إنشاء دفعة كروت وإضافتها فعلياً إلى المايكروتيك وطباعتها") },
                    leadingContent = { Icon(Icons.Rounded.Print, contentDescription = null, tint = ext.royalActionBlue) },
                    modifier = Modifier.clickable {
                        showQuickActionsSheet = false
                        onNavigate(AppRoutes.CARD_GENERATION)
                    }
                )
                ListItem(
                    headlineContent = { Text("تصميم قالب الكرت", fontWeight = FontWeight.Bold) },
                    supportingContent = { Text("تعديل أبعاد الكرت والخلفية والخط ورمز QR") },
                    leadingContent = { Icon(Icons.Rounded.Palette, contentDescription = null, tint = ext.reportPurple) },
                    modifier = Modifier.clickable {
                        showQuickActionsSheet = false
                        onNavigate(AppRoutes.CARD_DESIGNER)
                    }
                )
                ListItem(
                    headlineContent = { Text("معاينة طباعة A4", fontWeight = FontWeight.Bold) },
                    supportingContent = { Text("استعراض شبكة الكروت على ورق A4 وتصدير PDF") },
                    leadingContent = { Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, tint = ext.reportTeal) },
                    modifier = Modifier.clickable {
                        showQuickActionsSheet = false
                        onNavigate(AppRoutes.PRINT_PREVIEW)
                    }
                )
                ListItem(
                    headlineContent = { Text("إعدادات الطباعة A4 وقفل التطبيق", fontWeight = FontWeight.Bold) },
                    supportingContent = { Text("خيارات خطوط القص وحماية التطبيق بكلمة مرور أو البصمة") },
                    leadingContent = { Icon(Icons.Rounded.Settings, contentDescription = null, tint = ext.reportAmberGold) },
                    modifier = Modifier.clickable {
                        showQuickActionsSheet = false
                        onNavigate(AppRoutes.SETTINGS)
                    }
                )
            }
        }
    }
}

@Composable
private fun MetricGaugeCircle(
    label: String,
    valueText: String,
    waveFillFraction: Float,
    isLive: Boolean
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFCBD5E1),
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .size(74.dp)
                .clip(CircleShape)
                .background(if (isLive) Color(0xFFE2E8F0) else Color(0xFF1E293B))
                .border(
                    width = 4.dp,
                    color = if (isLive) Color(0xFFD9820B) else Color(0xFF475569),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isLive && waveFillFraction > 0f) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val h = size.height
                    val w = size.width
                    val fillTop = h * (1f - waveFillFraction.coerceIn(0.15f, 0.85f))
                    val path = Path().apply {
                        moveTo(0f, fillTop)
                        quadraticTo(w * 0.25f, fillTop - 10f, w * 0.5f, fillTop)
                        quadraticTo(w * 0.75f, fillTop + 10f, w, fillTop)
                        lineTo(w, h)
                        lineTo(0f, h)
                        close()
                    }
                    drawPath(path = path, color = Color(0xFFC2185B).copy(alpha = 0.88f))
                }
            }
            Text(
                text = valueText,
                color = if (isLive) Color(0xFF0F172A) else Color(0xFF94A3B8),
                fontWeight = FontWeight.ExtraBold,
                fontSize = if (valueText.length > 5) 11.sp else 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}
