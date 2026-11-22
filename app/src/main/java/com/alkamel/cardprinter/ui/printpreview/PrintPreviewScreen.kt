package com.alkamel.cardprinter.ui.printpreview

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alkamel.cardprinter.data.printing.PdfPrintEngine
import com.alkamel.cardprinter.ui.components.AlkamelTopHeader
import com.alkamel.cardprinter.ui.components.CardDesignPreviewBox
import com.alkamel.cardprinter.ui.theme.AlkamelTheme
import com.alkamel.cardprinter.ui.theme.SkyFabBlue

@Composable
fun PrintPreviewScreen(
    viewModel: PrintPreviewViewModel,
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

    val cols = state.activeTemplate.columnsPerPage.coerceIn(1, 6)
    val rows = state.activeTemplate.rowsPerPage.coerceIn(1, 30)
    val perPage = cols * rows
    val startIdx = state.currentPageIndex * perPage
    val pageCards = remember(state.cardsToPrint, startIdx, perPage) {
        if (startIdx >= state.cardsToPrint.size) emptyList()
        else state.cardsToPrint.subList(startIdx, minOf(startIdx + perPage, state.cardsToPrint.size))
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AlkamelTopHeader(
                title = "معاينة طباعة A4 (${state.cardsToPrint.size} كرت)",
                onNavigateBack = onNavigateBack
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    viewModel.generateAndPrintA4(context = context, launchDirectPrint = true) { pdfFile ->
                        PdfPrintEngine.printPdfWithAndroidPrintManager(
                            context = context,
                            pdfFile = pdfFile,
                            jobName = "الكامل-طباعة كروت A4"
                        )
                    }
                },
                containerColor = SkyFabBlue,
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.size(62.dp)
            ) {
                if (state.isGeneratingPdf) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(26.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Print,
                        contentDescription = "طباعة A4 مباشرة",
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.templates.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.templates.forEach { tpl ->
                        FilterChip(
                            selected = tpl.id == state.activeTemplate.id,
                            onClick = { viewModel.selectTemplate(tpl) },
                            label = { Text("${tpl.name} • ${tpl.packageName} (${tpl.columnsPerPage}×${tpl.rowsPerPage})") }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        viewModel.generateAndPrintA4(context = context, launchDirectPrint = false) { pdfFile ->
                            context.startActivity(PdfPrintEngine.createSharePdfIntent(context, pdfFile))
                        }
                    },
                    enabled = !state.isGeneratingPdf && state.cardsToPrint.isNotEmpty(),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("مشاركة PDF (A4)", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = {
                        viewModel.generateAndPrintA4(context = context, launchDirectPrint = false) { pdfFile ->
                            context.startActivity(PdfPrintEngine.createOpenPdfIntent(context, pdfFile))
                        }
                    },
                    enabled = !state.isGeneratingPdf && state.cardsToPrint.isNotEmpty(),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, tint = ext.destructivePink, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("فتح PDF (A4)", fontWeight = FontWeight.Bold)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = viewModel::previousPage,
                    enabled = state.currentPageIndex > 0
                ) {
                    Icon(Icons.Rounded.ChevronRight, contentDescription = "الصفحة السابقة")
                }
                Text(
                    text = "صفحة ${state.currentPageIndex + 1} من ${state.totalPages}  •  الشبكة: $cols × $rows",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = viewModel::nextPage,
                    enabled = state.currentPageIndex + 1 < state.totalPages
                ) {
                    Icon(Icons.Rounded.ChevronLeft, contentDescription = "الصفحة التالية")
                }
            }

            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFCBD5E1), RoundedCornerShape(8.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    if (state.settings.showHeaderOnA4) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "الكامل-طباعة الكروت • ${state.activeTemplate.name} (${state.activeTemplate.packageName})",
                                color = Color(0xFF0F172A),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "A4 • ${pageCards.size} كرت",
                                color = Color(0xFF475569),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }

                    if (pageCards.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "لا توجد كروت للمعاينة حالياً.",
                                color = Color(0xFF475569),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    } else {
                        val cardRatio = com.alkamel.cardprinter.data.printing.CardLayoutMetrics.computeCardAspectRatio(state.activeTemplate)
                        val chunkedRows = pageCards.chunked(cols)
                        chunkedRows.forEach { rowCards ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                rowCards.forEach { card ->
                                    CardDesignPreviewBox(
                                        template = state.activeTemplate,
                                        sampleUsername = card.username,
                                        samplePassword = card.password,
                                        selectedElement = null,
                                        isInteractive = false,
                                        onElementDragged = { _, _, _ -> },
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(cardRatio)
                                            .then(
                                                if (state.settings.showCutGuidesOnA4) {
                                                    Modifier.border(0.5.dp, Color(0xFF94A3B8))
                                                } else Modifier
                                            )
                                    )
                                }
                                repeat(cols - rowCards.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(72.dp))
        }
    }
}
