package com.alkamel.cardprinter.ui.expired

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alkamel.cardprinter.data.local.entity.CardEntity
import com.alkamel.cardprinter.ui.components.AlkamelConfirmationDialog
import com.alkamel.cardprinter.ui.components.AlkamelTopHeader
import com.alkamel.cardprinter.ui.theme.AlkamelTheme

@Composable
fun ExpiredCardsScreen(
    viewModel: ExpiredCardsViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val ext = AlkamelTheme.extendedColors
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var packageMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(state.statusMessage, state.errorMessage) {
        val msg = state.errorMessage ?: state.statusMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessages()
        }
    }

    val allSelected = state.filteredExpired.isNotEmpty() &&
        state.filteredExpired.all { it.id in state.selectedCardIds }
    val deleteTargetCount = if (state.selectedCardIds.isNotEmpty()) {
        state.selectedCardIds.size
    } else {
        state.filteredExpired.size
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AlkamelTopHeader(
                title = "حذف الكروت المنتهية (${state.unifiedExpiredCount})",
                onNavigateBack = onNavigateBack,
                trailingAction = {
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
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "تحديث الكروت المنتهية من يوزر منجر",
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
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Filter bar matching «حذف الكروت المنتهية» (الباقة + التحميل بالميقا + الوقت + زر البحث)
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Package dropdown selector
                        Box(modifier = Modifier.weight(1.05f)) {
                            OutlinedButton(
                                onClick = { packageMenuExpanded = true },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = state.selectedPackage,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(Icons.Rounded.ArrowDropDown, contentDescription = "اختر الباقة")
                            }
                            DropdownMenu(
                                expanded = packageMenuExpanded,
                                onDismissRequest = { packageMenuExpanded = false }
                            ) {
                                state.availablePackages.forEach { pkg ->
                                    DropdownMenuItem(
                                        text = { Text(pkg, fontWeight = FontWeight.SemiBold) },
                                        onClick = {
                                            packageMenuExpanded = false
                                            viewModel.onPackageSelected(pkg)
                                        }
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = state.minDownloadMbInput,
                            onValueChange = viewModel::onMinDownloadMbChange,
                            placeholder = { Text("التحميل بالميقا", maxLines = 1) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = ext.inputContainer,
                                unfocusedContainerColor = ext.inputContainer
                            ),
                            modifier = Modifier.weight(1.05f)
                        )

                        OutlinedTextField(
                            value = state.minUptimeInput,
                            onValueChange = viewModel::onMinUptimeChange,
                            placeholder = { Text("الوقت", maxLines = 1) },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = ext.inputContainer,
                                unfocusedContainerColor = ext.inputContainer
                            ),
                            modifier = Modifier.weight(0.75f)
                        )

                        IconButton(
                            onClick = viewModel::applyThresholdSearch,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(ext.reportPurple)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = "بحث حسب التحميل والوقت",
                                tint = Color.White
                            )
                        }
                    }

                    // Action buttons: «عرض الكروت منتهية الصلاحية» & «حذف الكروت المحددة»
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = viewModel::showAllExpiredCards,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF7B0F3B),
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                            modifier = Modifier.weight(1.25f)
                        ) {
                            Text(
                                text = "عرض الكروت منتهية الصلاحية",
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Button(
                            onClick = { showDeleteConfirmDialog = true },
                            enabled = state.filteredExpired.isNotEmpty() && !state.isDeleting,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ext.expiredBurgundy,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                            modifier = Modifier.weight(0.95f)
                        ) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (state.selectedCardIds.isNotEmpty()) "حذف المحددة" else "حذف الكل",
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }

                    // Quick search by card username
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = viewModel::onSearchQueryChange,
                        placeholder = { Text("بحث برقم الكرت أو الباقة...") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = ext.inputContainer,
                            unfocusedContainerColor = ext.inputContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Selection bar: «تحديد الكل» | «عدد الكروت المحددة : X» | «المعروض : Y»
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { viewModel.toggleSelectAll() }
                ) {
                    Checkbox(
                        checked = allSelected,
                        onCheckedChange = { viewModel.toggleSelectAll() },
                        colors = CheckboxDefaults.colors(checkedColor = ext.royalActionBlue)
                    )
                    Text(
                        text = "تحديد الكل",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "عدد الكروت المحددة : ${state.selectedCardIds.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = "المعروض: ${state.filteredExpired.size}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = ext.expiredBurgundy
                )
            }

            if (state.filteredExpired.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (state.isThresholdFilterActive) {
                            "لا توجد كروت تطابق حد التحميل أو الوقت المدخل.\nاضغط «عرض الكروت منتهية الصلاحية» أو عدّل القيم."
                        } else {
                            "لا توجد كروت منتهية حالياً.\nاضغط أيقونة التحديث بالأعلى لجلب أحدث حالة وحدود الصلاحية من يوزر منجر، أو ابحث بحد التحميل بالميقا والوقت."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 20.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(state.filteredExpired, key = { it.id }) { card ->
                        BurgundyExpiredCard(
                            card = card,
                            isSelected = card.id in state.selectedCardIds,
                            onToggleSelect = { viewModel.toggleCardSelection(card.id) },
                            onRestore = { viewModel.restoreCard(card) },
                            onDelete = { viewModel.deleteSingleCard(card) }
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirmDialog) {
        AlkamelConfirmationDialog(
            title = if (state.selectedCardIds.isNotEmpty()) "حذف الكروت المحددة" else "حذف جميع الكروت المنتهية",
            message = "هل تريد حذف $deleteTargetCount كرت منتهي نهائياً من يوزر منجر وقاعدة البيانات؟",
            confirmText = "حذف الآن",
            dismissText = "إلغاء",
            isDestructive = true,
            onConfirm = {
                showDeleteConfirmDialog = false
                viewModel.deleteSelectedOrAllExpired()
            },
            onDismiss = { showDeleteConfirmDialog = false }
        )
    }
}

@Composable
private fun BurgundyExpiredCard(
    card: CardEntity,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    val ext = AlkamelTheme.extendedColors
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ext.expiredBurgundy),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleSelect() }
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect() },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color.White,
                            uncheckedColor = Color.White.copy(alpha = 0.85f),
                            checkmarkColor = ext.expiredBurgundy
                        )
                    )
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(ext.expiredCoralBadge),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AccountBalanceWallet,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = card.username,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                    if (card.packageName.isNotBlank()) {
                        Text(
                            text = card.packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }

                Row {
                    IconButton(onClick = onRestore) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "إعادة تفعيل", tint = Color(0xFFFFF59D))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "حذف", tint = Color.White)
                    }
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.3f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "الوقت : ${card.uptimeLabel}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "التحميل : ${card.dataUsageLabel}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
