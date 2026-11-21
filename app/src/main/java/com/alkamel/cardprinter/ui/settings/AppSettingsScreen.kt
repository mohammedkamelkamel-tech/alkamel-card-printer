package com.alkamel.cardprinter.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alkamel.cardprinter.ui.components.AlkamelTextField
import com.alkamel.cardprinter.ui.components.AlkamelTopHeader
import com.alkamel.cardprinter.ui.theme.AlkamelTheme

@Composable
fun AppSettingsScreen(
    viewModel: AppSettingsViewModel,
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
                title = "إعدادات الطباعة A4 والأمان",
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
            // A4 Paper Printing Settings Card
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Print, contentDescription = null, tint = ext.royalActionBlue)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "إعدادات الطباعة الورقية (مقاس A4)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "يعتمد التطبيق الطباعة الورقية بمقاس ISO A4 عبر توليد ملفات PDF عالية الدقة والطباعة المباشرة عبر خدمة طباعة أندرويد.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("إظهار خطوط القص حول الكروت", fontWeight = FontWeight.SemiBold)
                            Text("تسهيل قص ورقة A4 بدقة بعد الطباعة", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = state.settings.showCutGuidesOnA4,
                            onCheckedChange = viewModel::onToggleCutGuides
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("إظهار ترويسة الصفحة العلوية", fontWeight = FontWeight.SemiBold)
                            Text("عرض اسم التطبيق ورقم الصفحة والتاريخ أعلى ورقة A4", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = state.settings.showHeaderOnA4,
                            onCheckedChange = viewModel::onToggleA4Header
                        )
                    }
                }
            }

            // Optional App Lock Card (Password + Biometric)
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lock, contentDescription = null, tint = ext.reportPurple)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "حماية وقفل التطبيق (اختياري)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = if (state.settings.appLockEnabled) "حالة القفل: مفعّل" else "حالة القفل: غير مفعّل",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (state.settings.appLockEnabled) ext.voucherGreen else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    AlkamelTextField(
                        value = state.newPasswordInput,
                        onValueChange = viewModel::onNewPasswordChange,
                        label = if (state.settings.appLockEnabled) "كلمة مرور جديدة لتغيير القفل" else "كلمة مرور قفل التطبيق",
                        leadingIcon = Icons.Rounded.Lock,
                        isPassword = true
                    )
                    AlkamelTextField(
                        value = state.confirmPasswordInput,
                        onValueChange = viewModel::onConfirmPasswordChange,
                        label = "تأكيد كلمة المرور",
                        leadingIcon = Icons.Rounded.Lock,
                        isPassword = true
                    )

                    Button(
                        onClick = viewModel::enableAppLock,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ext.royalActionBlue, contentColor = Color.White),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(
                            text = if (state.settings.appLockEnabled) "تحديث كلمة مرور القفل" else "تفعيل قفل التطبيق",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (state.settings.appLockEnabled) {
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.Fingerprint, contentDescription = null, tint = ext.royalActionBlue)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("السماح بالفتح عبر البصمة", fontWeight = FontWeight.SemiBold)
                            }
                            Switch(
                                checked = state.settings.biometricEnabled,
                                onCheckedChange = viewModel::toggleBiometric
                            )
                        }

                        OutlinedButton(
                            onClick = viewModel::disableAppLock,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("إلغاء قفل التطبيق", color = ext.destructivePink, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
