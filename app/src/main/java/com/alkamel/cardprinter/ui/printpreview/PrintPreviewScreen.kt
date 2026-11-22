package com.alkamel.cardprinter.ui.printpreview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.alkamel.cardprinter.data.printing.PdfPrintEngine
import com.alkamel.cardprinter.ui.components.AlkamelTopHeader
import com.alkamel.cardprinter.ui.theme.AlkamelTheme
import com.alkamel.cardprinter.ui.theme.SkyFabBlue
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
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
            // Template Selector Cards showing each template's unique image, name, and bound User Manager package
            if (state.templates.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.templates.forEach { tpl ->
                        val isSelected = tpl.id == state.activeTemplate.id
                        val thumbFile = remember(tpl.backgroundImagePath) {
                            tpl.backgroundImagePath
                                ?.takeIf { it.isNotBlank() }
                                ?.let { File(it) }
                                ?.takeIf { it.exists() && it.length() > 0L }
                        }
                        Card(
                            onClick = { viewModel.selectTemplate(tpl) },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) ext.royalActionBlue.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface
                            ),
                            border = BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) ext.royalActionBlue else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier.width(172.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(width = 44.dp, height = 30.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(
                                            Brush.linearGradient(listOf(Color(0xFF0F2342), Color(0xFF1D4E89)))
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (thumbFile != null) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(context).data(thumbFile).crossfade(false).build(),
                                            contentDescription = tpl.name,
                                            contentScale = ContentScale.FillBounds,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Rounded.Image,
                                            contentDescription = null,
                                            tint = Color.White.copy(alpha = 0.8f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = tpl.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "باقة: ${tpl.packageName}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ext.royalActionBlue,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${tpl.columnsPerPage}×${tpl.rowsPerPage}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
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
                    text = "صفحة ${state.currentPageIndex + 1} من ${state.totalPages}  •  $cols × $rows ($perPage كرت/صفحة)",
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

            // True ISO A4 Page Sheet Preview (100% identical to exported PDF)
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.5.dp, Color(0xFFCBD5E1), RoundedCornerShape(10.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(210f / 297f)
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = state.previewPageBitmap
                    when {
                        state.cardsToPrint.isEmpty() -> {
                            Text(
                                text = "لا توجد كروت للمعاينة حالياً.",
                                color = Color(0xFF475569),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        state.isRenderingPreview && bmp == null -> {
                            CircularProgressIndicator(color = ext.royalActionBlue)
                        }
                        bmp != null -> {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "معاينة صفحة A4 المطابقة لملف PDF",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(72.dp))
        }
    }
}
