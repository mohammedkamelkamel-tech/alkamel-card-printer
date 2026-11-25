package com.alkamel.cardprinter.ui.cards

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alkamel.cardprinter.data.local.entity.CardEntity
import com.alkamel.cardprinter.data.local.entity.CardSessionEntity
import com.alkamel.cardprinter.data.local.model.CardStatus
import com.alkamel.cardprinter.ui.components.AlkamelConfirmationDialog
import com.alkamel.cardprinter.ui.components.AlkamelTopHeader
import com.alkamel.cardprinter.ui.theme.AlkamelTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserManagerCardsScreen(
    viewModel: UserManagerCardsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToPrintPreview: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val ext = AlkamelTheme.extendedColors
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var pendingDeleteSingleCard by remember { mutableStateOf<CardEntity?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val fileName = resolveFileName(context, uri)
            val content = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull().orEmpty()
            viewModel.importCardsFromFile(content, fileName)
        }
    }

    LaunchedEffect(state.statusMessage, state.errorMessage) {
        val msg = state.errorMessage ?: state.statusMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessages()
        }
    }

    val categories = state.availableCategories

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AlkamelTopHeader(
                title = "كروت يوزر منجر",
                onNavigateBack = onNavigateBack,
                trailingAction = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = viewModel::syncFromMikrotik,
                            enabled = !state.isSyncing
                        ) {
                            if (state.isSyncing) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(20.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Sync,
                                    contentDescription = "مزامنة من المايكروتيك",
                                    tint = Color.White
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                importLauncher.launch(arrayOf("text/plain", "text/csv", "text/*", "application/octet-stream"))
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FileUpload,
                                contentDescription = "استيراد TXT/CSV",
                                tint = Color.White
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.printDateQuery,
                    onValueChange = viewModel::onPrintDateQueryChange,
                    placeholder = { Text("تاريخ الطباعة (مثال: 2026-11-21)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = ext.inputContainer,
                        unfocusedContainerColor = ext.inputContainer,
                        focusedTextColor = ext.inputText,
                        unfocusedTextColor = ext.inputText
                    )
                )
                Button(
                    onClick = viewModel::applyPrintDateSearch,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ext.royalActionBlue, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp)
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("بحث", fontWeight = FontWeight.Bold)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val statusItems = listOf<Pair<String, CardStatus?>>(
                    "الكل" to null,
                    "غير مستخدم" to CardStatus.UNUSED,
                    "مستخدم" to CardStatus.USED,
                    "منتهي" to CardStatus.EXPIRED
                )
                statusItems.forEach { (label, status) ->
                    FilterChip(
                        selected = state.selectedStatus == status,
                        onClick = { viewModel.onStatusSelected(status) },
                        label = { Text(label, fontWeight = FontWeight.SemiBold) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ext.royalActionBlue,
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { cat ->
                    FilterChip(
                        selected = state.selectedCategory == cat,
                        onClick = { viewModel.onCategorySelected(cat) },
                        label = { Text(cat, fontWeight = FontWeight.SemiBold) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ext.voucherGreen,
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = buildString {
                            append("إجمالي الكروت: ${state.totalCardsCount}")
                            if (state.hasActiveFilter) {
                                append("  •  المعروض: ${state.filteredCards.size}")
                            }
                            if (state.selectedCardIds.isNotEmpty()) {
                                append("  •  محدد: ${state.selectedCardIds.size}")
                            }
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "اضغط على أي كرت لعرض جلساته • اضغط الأيقونة للتحديد",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (state.appliedPrintDate.isNotBlank()) {
                        Text(
                            text = "مسح التاريخ",
                            color = ext.destructivePink,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { viewModel.clearDateSearch() }
                        )
                    }
                    IconButton(onClick = viewModel::toggleSelectAllFiltered) {
                        Icon(
                            imageVector = Icons.Rounded.SelectAll,
                            contentDescription = "تحديد الكل",
                            tint = ext.royalActionBlue
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = viewModel::reAddSelectedCards,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ext.reportTeal, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    modifier = Modifier.weight(1.15f)
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("إعادة الإضافة", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = {
                        if (viewModel.dispatchCardsToA4Print()) {
                            onNavigateToPrintPreview()
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ext.royalActionBlue, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    modifier = Modifier.weight(0.95f)
                ) {
                    Icon(Icons.Rounded.Print, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("طباعة", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { showDeleteConfirm = true },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ext.destructivePink, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    modifier = Modifier.weight(0.85f)
                ) {
                    Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("حذف", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }

            if (state.filteredCards.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (state.allCards.isEmpty()) {
                            "لا توجد كروت مجلوبة بعد. اربط بالمايكروتيك واضغط زر المزامنة (أعلى الشاشة) أو ولّد كروتاً جديدة."
                        } else {
                            "لا توجد كروت مطابقة لمعايير البحث الحالية."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 20.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(state.filteredCards, key = { it.id }) { card ->
                        GreenVoucherCard(
                            card = card,
                            isSelected = card.id in state.selectedCardIds,
                            onCardClick = { viewModel.openCardDetails(card) },
                            onToggleSelect = { viewModel.toggleCardSelection(card.id) }
                        )
                    }
                }
            }
        }
    }

    // Card Sessions Detail BottomSheet (opens when tapping any card)
    state.inspectedCard?.let { card ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val isCardSelected = card.id in state.selectedCardIds

        ModalBottomSheet(
            onDismissRequest = viewModel::closeCardDetails,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            CardSessionsDetailSheetContent(
                card = card,
                isSelected = isCardSelected,
                sessions = state.inspectedCardSessions,
                isLoading = state.isLoadingCardSessions,
                sourceNote = state.cardSessionsSourceNote,
                errorMessage = state.cardSessionsError,
                onRefreshSessions = viewModel::refreshInspectedCardSessions,
                onToggleSelect = { viewModel.toggleCardSelection(card.id) },
                onPrintSingleCard = {
                    if (viewModel.dispatchSingleCardToA4Print(card)) {
                        viewModel.closeCardDetails()
                        onNavigateToPrintPreview()
                    }
                },
                onDeleteSingleCard = {
                    pendingDeleteSingleCard = card
                }
            )
        }
    }

    if (showDeleteConfirm) {
        AlkamelConfirmationDialog(
            title = "تأكيد حذف الكروت",
            message = "هل تريد حذف ${viewModel.getActionTargetCount()} كرت من القائمة الحالية؟ سيتم حذفها من المايكروتيك أيضاً عند توفر الاتصال.",
            confirmText = "حذف نهائي",
            dismissText = "إلغاء",
            isDestructive = true,
            onConfirm = {
                showDeleteConfirm = false
                viewModel.deleteSelectedCards()
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }

    pendingDeleteSingleCard?.let { cardToDelete ->
        AlkamelConfirmationDialog(
            title = "حذف الكرت (${cardToDelete.username})",
            message = "هل تريد حذف هذا الكرت نهائياً من قاعدة البيانات والمايكروتيك؟",
            confirmText = "حذف نهائي",
            dismissText = "إلغاء",
            isDestructive = true,
            onConfirm = {
                pendingDeleteSingleCard = null
                viewModel.deleteSingleCard(cardToDelete)
            },
            onDismiss = { pendingDeleteSingleCard = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GreenVoucherCard(
    card: CardEntity,
    isSelected: Boolean,
    onCardClick: () -> Unit,
    onToggleSelect: () -> Unit
) {
    val ext = AlkamelTheme.extendedColors
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ext.voucherGreen),
        border = if (isSelected) BorderStroke(3.dp, Color(0xFFFFCA28)) else null,
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onCardClick,
                onLongClick = onToggleSelect
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) Color(0xFFFFCA28).copy(alpha = 0.28f)
                            else Color.White.copy(alpha = 0.20f)
                        )
                        .clickable(onClick = onToggleSelect),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.AccountBalanceWallet,
                        contentDescription = "تحديد الكرت",
                        tint = if (isSelected) Color(0xFFFFCA28) else Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "اسم المستخدم",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                    Text(
                        text = card.username,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(0.9f)
                ) {
                    Text(
                        text = "كلمة السر",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                    Text(
                        text = card.password.ifBlank { "بدون" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.35f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "الباقة: ${card.packageName.ifBlank { "-" }}" +
                        if (card.price > 0) " (${card.price.toLong()} ريال)" else "",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "الاستهلاك: ${card.dataUsageLabel}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "الحالة: ${card.status.arabicLabel}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFE8F5E9)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (card.isPrinted) "مطبوع: ${card.printedDateKey.orEmpty()}" else "غير مطبوع",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFFFF59D),
                        fontWeight = FontWeight.SemiBold
                    )
                    Surface(
                        color = Color.White.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.History,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "الجلسات",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardSessionsDetailSheetContent(
    card: CardEntity,
    isSelected: Boolean,
    sessions: List<CardSessionEntity>,
    isLoading: Boolean,
    sourceNote: String,
    errorMessage: String?,
    onRefreshSessions: () -> Unit,
    onToggleSelect: () -> Unit,
    onPrintSingleCard: () -> Unit,
    onDeleteSingleCard: () -> Unit
) {
    val ext = AlkamelTheme.extendedColors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Card Header Summary
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = ext.headerNavy),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "تفاصيل وجلسات الكرت",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF94A3B8)
                        )
                        Text(
                            text = card.username,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    }
                    IconButton(
                        onClick = onRefreshSessions,
                        enabled = !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "تحديث جلسات الكرت",
                                tint = Color.White
                            )
                        }
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.16f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "كلمة السر: ${card.password.ifBlank { "بدون" }}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFE2E8F0)
                    )
                    Text(
                        text = "الباقة: ${card.packageName.ifBlank { "-" }}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFFFF59D),
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "الحالة: ${card.status.arabicLabel}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF81C784),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "إجمالي الاستهلاك: ${card.dataUsageLabel} • المدة: ${card.uptimeLabel}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White
                    )
                }
            }
        }

        // Quick Action Buttons without conflicting with list selection
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onToggleSelect,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = if (isSelected) ext.voucherGreen else ext.royalActionBlue,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (isSelected) "إلغاء التحديد" else "تحديد",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Button(
                onClick = onPrintSingleCard,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ext.royalActionBlue, contentColor = Color.White),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Rounded.Print, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("طباعة", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onDeleteSingleCard,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ext.destructivePink, contentColor = Color.White),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                modifier = Modifier.weight(0.9f)
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("حذف", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }

        // Sessions Section Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "سجل جلسات الكرت (${sessions.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (sourceNote.isNotBlank()) {
                Text(
                    text = sourceNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!errorMessage.isNullOrBlank()) {
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = ext.destructivePink.copy(alpha = 0.12f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.labelMedium,
                    color = ext.destructivePink,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        if (isLoading && sessions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(color = ext.royalActionBlue)
                    Text(
                        text = "جاري جلب جلسات الكرت من المايكروتيك...",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (sessions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "لا توجد جلسات مسجلة لهذا الكرت بعد.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(sessions) { sess ->
                    SessionDetailItemCard(session = sess)
                }
            }
        }
    }
}

@Composable
private fun SessionDetailItemCard(session: CardSessionEntity) {
    val ext = AlkamelTheme.extendedColors
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (session.isActive) ext.voucherGreen.copy(alpha = 0.10f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        ),
        border = if (session.isActive) BorderStroke(1.5.dp, ext.voucherGreen) else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (session.isActive) ext.voucherGreen else Color(0xFF64748B)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (session.isActive) Icons.Rounded.Wifi else Icons.Rounded.Schedule,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = if (session.isActive) "جلسة نشطة الآن" else "جلسة منتهية",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    text = "المدة: ${session.uptime}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = ext.royalActionBlue
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "البدء: ${session.startTime}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "الانتهاء: ${session.endTime}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "التحميل: ${session.downloadLabel}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = ext.voucherGreen
                )
                Text(
                    text = "الرفع: ${session.uploadLabel}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = ext.reportAmberGold
                )
                Text(
                    text = "الإجمالي: ${session.totalLabel}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Router,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "IP: ${session.userIp}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "MAC: ${session.macAddress}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun resolveFileName(context: android.content.Context, uri: Uri): String {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIdx >= 0 && cursor.moveToFirst()) {
            return cursor.getString(nameIdx) ?: "imported_cards.txt"
        }
    }
    return uri.lastPathSegment ?: "imported_cards.txt"
}
