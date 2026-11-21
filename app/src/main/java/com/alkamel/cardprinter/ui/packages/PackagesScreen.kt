package com.alkamel.cardprinter.ui.packages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.alkamel.cardprinter.ui.components.AlkamelTopHeader
import com.alkamel.cardprinter.ui.theme.AlkamelTheme
import com.alkamel.cardprinter.ui.theme.SkyFabBlue

@Composable
fun PackagesScreen(
    viewModel: PackagesViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val ext = AlkamelTheme.extendedColors
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
                title = "إدارة الباقات (${state.packages.size})",
                onNavigateBack = onNavigateBack
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = viewModel::openAddDialog,
                containerColor = SkyFabBlue,
                contentColor = Color.White,
                shape = CircleShape
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "إضافة باقة")
            }
        }
    ) { innerPadding ->
        if (state.packages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "لا توجد باقات حالياً.\nقم بالربط بسيرفر المايكروتيك ومزامنة الباقات تلقائياً أو أضف باقة مطابقة لاسم الـ Profile في المايكروتيك عبر زر (+).",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.packages, key = { it.id }) { pkg ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(ext.reportAmberGold.copy(alpha = 0.16f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Rounded.Folder, contentDescription = null, tint = ext.reportAmberGold)
                                }
                                Column {
                                    Text(
                                        text = "باقة ${pkg.name}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "السعر: ${pkg.price.toLong()} ريال • العمولة: ${pkg.commission.toLong()} ريال",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ext.voucherGreen,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (pkg.validityLabel.isNotBlank() || pkg.dataLimitMb > 0) {
                                        Text(
                                            text = "الصلاحية: ${pkg.validityLabel.ifBlank { "-" }}" +
                                                if (pkg.dataLimitMb > 0) " • الحجم: ${pkg.dataLimitMb.toLong()} MB" else "",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Row {
                                IconButton(onClick = { viewModel.openEditDialog(pkg) }) {
                                    Icon(Icons.Rounded.Edit, contentDescription = "تعديل", tint = ext.royalActionBlue)
                                }
                                IconButton(onClick = { viewModel.deletePackage(pkg) }) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "حذف", tint = ext.destructivePink)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.showEditorDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = {
                Text(
                    text = if (state.editingId == 0L) "إضافة باقة جديدة" else "تعديل الباقة",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.nameInput,
                        onValueChange = viewModel::onNameChange,
                        label = { Text("اسم الباقة (Profile في المايكروتيك)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = state.priceInput,
                            onValueChange = viewModel::onPriceChange,
                            label = { Text("السعر (ريال)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = state.commissionInput,
                            onValueChange = viewModel::onCommissionChange,
                            label = { Text("العمولة") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = state.validityInput,
                            onValueChange = viewModel::onValidityChange,
                            label = { Text("الصلاحية") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = state.dataLimitMbInput,
                            onValueChange = viewModel::onDataLimitChange,
                            label = { Text("الحجم MB") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = viewModel::savePackage,
                    colors = ButtonDefaults.buttonColors(containerColor = ext.royalActionBlue, contentColor = Color.White)
                ) {
                    Text("حفظ", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDialog) {
                    Text("إلغاء")
                }
            }
        )
    }
}
