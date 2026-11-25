package com.alkamel.cardprinter.ui.expired

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
    var showDeleteAllDialog by remember { mutableStateOf(false) }

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
                title = "كروت يوزر منجر المنتهية (${state.filteredExpired.size})",
                onNavigateBack = onNavigateBack,
                trailingAction = {
                    IconButton(
                        onClick = viewModel::syncFromMikrotik,
                        enabled = !state.isSyncing && state.isConnectedToMikrotik
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = viewModel::onSearchQueryChange,
                    placeholder = { Text("بحث برقم الكرت أو الباقة...") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = ext.inputContainer,
                        unfocusedContainerColor = ext.inputContainer
                    ),
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = { showDeleteAllDialog = true },
                    enabled = state.filteredExpired.isNotEmpty(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ext.expiredBurgundy, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)
                ) {
                    Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("حذف الكل", fontWeight = FontWeight.Bold)
                }
            }

            if (state.filteredExpired.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "لا توجد كروت منتهية حالياً.",
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
                    items(state.filteredExpired, key = { it.id }) { card ->
                        BurgundyExpiredCard(
                            card = card,
                            onRestore = { viewModel.restoreCard(card) },
                            onDelete = { viewModel.deleteSingleCard(card) }
                        )
                    }
                }
            }
        }
    }

    if (showDeleteAllDialog) {
        AlkamelConfirmationDialog(
            title = "حذف جميع الكروت المنتهية",
            message = "هل تريد حذف ${state.filteredExpired.size} كرت منتهي نهائياً من قاعدة البيانات؟",
            confirmText = "حذف الكل",
            dismissText = "إلغاء",
            isDestructive = true,
            onConfirm = {
                showDeleteAllDialog = false
                viewModel.deleteAllExpired()
            },
            onDismiss = { showDeleteAllDialog = false }
        )
    }
}

@Composable
private fun BurgundyExpiredCard(
    card: CardEntity,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    val ext = AlkamelTheme.extendedColors
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ext.expiredBurgundy),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = Modifier.fillMaxWidth()
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
                        .background(ext.expiredCoralBadge),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.PersonOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("اسم المستخدم", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f))
                    Text(card.username, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Color.White)
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
                Text("الباقة: ${card.packageName.ifBlank { "-" }}", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text("الاستهلاك: ${card.dataUsageLabel}", color = Color(0xFFFFCDD2), style = MaterialTheme.typography.labelMedium)
                Text("المدة: ${card.uptimeLabel}", color = Color(0xFFFFCDD2), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
