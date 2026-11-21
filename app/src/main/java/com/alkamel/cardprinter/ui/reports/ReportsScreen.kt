package com.alkamel.cardprinter.ui.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.alkamel.cardprinter.data.printing.PdfPrintEngine
import com.alkamel.cardprinter.ui.components.AlkamelTopHeader
import com.alkamel.cardprinter.ui.theme.AlkamelTheme

@Composable
fun ReportsScreen(
    viewModel: ReportsViewModel,
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AlkamelTopHeader(
                title = "التقارير والإحصائيات",
                onNavigateBack = onNavigateBack
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Top 3 Percentage Indicators
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PercentageMetricCard(
                    title = "الكروت المنتهية",
                    percent = state.expiredPercent,
                    subtitle = "${state.expiredCardsCount} كرت",
                    color = ext.expiredBurgundy,
                    modifier = Modifier.weight(1f)
                )
                PercentageMetricCard(
                    title = "الكروت المستخدمة",
                    percent = state.usedPercent,
                    subtitle = "${state.usedCardsCount} كرت",
                    color = ext.reportBlue,
                    modifier = Modifier.weight(1f)
                )
                PercentageMetricCard(
                    title = "تحميل اليوم",
                    percent = state.todayDownloadPercent,
                    subtitle = String.format("%.2f GB", state.todayDownloadGb),
                    color = ext.reportTeal,
                    modifier = Modifier.weight(1f)
                )
            }

            // Sales Bars Card (اليوم، أمس، أسبوعياً، شهرياً)
            val maxAmount = maxOf(
                1.0,
                state.report.today.totalAmount,
                state.report.yesterday.totalAmount,
                state.report.weekly.totalAmount,
                state.report.monthly.totalAmount
            )

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "ملخص المبيعات حسب الفترة",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    SalesPeriodBar(
                        label = "مبيعات اليوم",
                        amount = state.report.today.totalAmount,
                        cards = state.report.today.totalCards,
                        fraction = (state.report.today.totalAmount / maxAmount).toFloat(),
                        color = ext.voucherGreen
                    )
                    SalesPeriodBar(
                        label = "مبيعات أمس",
                        amount = state.report.yesterday.totalAmount,
                        cards = state.report.yesterday.totalCards,
                        fraction = (state.report.yesterday.totalAmount / maxAmount).toFloat(),
                        color = ext.reportAmberGold
                    )
                    SalesPeriodBar(
                        label = "المبيعات الأسبوعية",
                        amount = state.report.weekly.totalAmount,
                        cards = state.report.weekly.totalCards,
                        fraction = (state.report.weekly.totalAmount / maxAmount).toFloat(),
                        color = ext.reportBlue
                    )
                    SalesPeriodBar(
                        label = "المبيعات الشهرية",
                        amount = state.report.monthly.totalAmount,
                        cards = state.report.monthly.totalCards,
                        fraction = (state.report.monthly.totalAmount / maxAmount).toFloat(),
                        color = ext.reportPurple
                    )
                }
            }

            // Today Download & Inventory Info Cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = ext.reportPurple),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("معلومات تحميل اليوم", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = String.format("%.2f GB", state.todayDownloadGb),
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "الإجمالي: ${String.format("%.2f GB", state.totalDownloadGb)}",
                            color = Color(0xFFE1BEE7),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = ext.reportTeal),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("الأكثر مبيعاً", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = state.report.topSellingPackage?.packageName ?: "لا يوجد",
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "متبقي غير مستخدم: ${state.unusedCardsCount}",
                            color = Color(0xFFB2DFDB),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            // A4 PDF Report Export & Direct Print
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("تصدير وطباعة التقرير (ورق A4)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.exportSalesReportPdf(context) { pdfFile ->
                                    PdfPrintEngine.printPdfWithAndroidPrintManager(
                                        context = context,
                                        pdfFile = pdfFile,
                                        jobName = "الكامل - تقرير المبيعات A4"
                                    )
                                }
                            },
                            enabled = !state.isExportingPdf,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ext.royalActionBlue, contentColor = Color.White),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("طباعة التقرير A4", fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                viewModel.exportSalesReportPdf(context) { pdfFile ->
                                    context.startActivity(PdfPrintEngine.createSharePdfIntent(context, pdfFile))
                                }
                            },
                            enabled = !state.isExportingPdf,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("مشاركة PDF", fontWeight = FontWeight.Bold)
                        }
                    }

                    state.lastExportedPdf?.let { file ->
                        OutlinedButton(
                            onClick = { context.startActivity(PdfPrintEngine.createOpenPdfIntent(context, file)) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, tint = ext.destructivePink)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("فتح آخر ملف PDF محفوظ (${file.name})")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PercentageMetricCard(
    title: String,
    percent: Int,
    subtitle: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(62.dp)) {
                CircularProgressIndicator(
                    progress = { (percent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxSize(),
                    color = color,
                    trackColor = color.copy(alpha = 0.16f),
                    strokeWidth = 6.dp
                )
                Text(
                    text = "$percent%",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SalesPeriodBar(
    label: String,
    amount: Double,
    cards: Int,
    fraction: Float,
    color: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = "${amount.toLong()} ريال ($cards كرت)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0.03f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(8.dp)),
            color = color,
            trackColor = color.copy(alpha = 0.14f),
            strokeCap = StrokeCap.Round
        )
    }
}
