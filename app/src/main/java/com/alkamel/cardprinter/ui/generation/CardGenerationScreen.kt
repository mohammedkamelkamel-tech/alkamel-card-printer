package com.alkamel.cardprinter.ui.generation

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.alkamel.cardprinter.data.local.entity.CardEntity
import com.alkamel.cardprinter.data.util.GenerationCharset
import com.alkamel.cardprinter.ui.components.AlkamelTopHeader
import com.alkamel.cardprinter.ui.theme.AlkamelTheme

private val CompletedExitRoyalBlue = Color(0xFF1976D2)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardGenerationScreen(
    viewModel: CardGenerationViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToDesigner: () -> Unit,
    onNavigateToPrintPreview: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val ext = AlkamelTheme.extendedColors
    val snackbarHostState = remember { SnackbarHostState() }

    var accountExpanded by remember { mutableStateOf(false) }
    var packageExpanded by remember { mutableStateOf(false) }
    var posExpanded by remember { mutableStateOf(false) }

    val accountOptions = remember(state.accountName) {
        listOf(state.accountName, "admin", "manager").distinct().filter { it.isNotBlank() }
    }
    val posOptions = remember(state.packages, state.pointOfSale) {
        (listOf("ether3", "ether4", "ether6", "نقطة السوق", "الفرع الرئيسي") +
            state.packages.map { it.pointOfSale } + state.pointOfSale)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

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
                title = "طباعة كروت يوزر مانجر",
                onNavigateBack = onNavigateBack,
                trailingAction = {
                    Button(
                        onClick = onNavigateBack,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.additionCompleted) CompletedExitRoyalBlue else Color.White.copy(alpha = 0.16f),
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ExitToApp,
                            contentDescription = "خروج",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("خروج", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Account Selector (admin)
                        ExposedDropdownMenuBox(
                            expanded = accountExpanded,
                            onExpandedChange = { accountExpanded = !accountExpanded }
                        ) {
                            OutlinedTextField(
                                value = state.accountName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("الحساب / السيرفر") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = accountExpanded) },
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = ext.inputContainer,
                                    unfocusedContainerColor = ext.inputContainer,
                                    focusedTextColor = ext.inputText,
                                    unfocusedTextColor = ext.inputText
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            )
                            ExposedDropdownMenu(
                                expanded = accountExpanded,
                                onDismissRequest = { accountExpanded = false }
                            ) {
                                accountOptions.forEach { acc ->
                                    DropdownMenuItem(
                                        text = { Text(acc) },
                                        onClick = {
                                            viewModel.onAccountChange(acc)
                                            accountExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        // 2x2 Radio Options matching reference layout
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { viewModel.onCharsetChange(GenerationCharset.NUMBERS_ONLY) }
                                ) {
                                    RadioButton(
                                        selected = state.charset == GenerationCharset.NUMBERS_ONLY,
                                        onClick = { viewModel.onCharsetChange(GenerationCharset.NUMBERS_ONLY) },
                                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF0288D1))
                                    )
                                    Text("ارقام فقط", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { viewModel.onCharsetChange(GenerationCharset.ALPHANUMERIC) }
                                ) {
                                    RadioButton(
                                        selected = state.charset == GenerationCharset.ALPHANUMERIC,
                                        onClick = { viewModel.onCharsetChange(GenerationCharset.ALPHANUMERIC) },
                                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF0288D1))
                                    )
                                    Text("ارقام وحروف", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                }
                            }

                            Column(modifier = Modifier.weight(1.25f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { viewModel.onUsernameOnlyChange(true) }
                                ) {
                                    RadioButton(
                                        selected = state.usernameOnly,
                                        onClick = { viewModel.onUsernameOnlyChange(true) },
                                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF0288D1))
                                    )
                                    Text("اسم مستخدم فقط", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { viewModel.onUsernameOnlyChange(false) }
                                ) {
                                    RadioButton(
                                        selected = !state.usernameOnly,
                                        onClick = { viewModel.onUsernameOnlyChange(false) },
                                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF0288D1))
                                    )
                                    Text("اسم المستخدم وكلمة المرور", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        // Package Name + Price Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ExposedDropdownMenuBox(
                                expanded = packageExpanded,
                                onExpandedChange = { packageExpanded = !packageExpanded },
                                modifier = Modifier.weight(1.2f)
                            ) {
                                OutlinedTextField(
                                    value = state.selectedPackageName,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("اسم الباقة") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = packageExpanded) },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = ext.inputContainer,
                                        unfocusedContainerColor = ext.inputContainer,
                                        focusedTextColor = ext.inputText,
                                        unfocusedTextColor = ext.inputText
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                )
                                ExposedDropdownMenu(
                                    expanded = packageExpanded,
                                    onDismissRequest = { packageExpanded = false }
                                ) {
                                    state.packages.forEach { pkg ->
                                        DropdownMenuItem(
                                            text = { Text("${pkg.name} (${pkg.price.toLong()} ريال)") },
                                            onClick = {
                                                viewModel.onPackageSelected(pkg)
                                                packageExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = state.priceInput,
                                onValueChange = viewModel::onPriceChange,
                                label = { Text("السعر (ريال)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                shape = RoundedCornerShape(10.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = ext.inputContainer,
                                    unfocusedContainerColor = ext.inputContainer,
                                    focusedTextColor = ext.inputText,
                                    unfocusedTextColor = ext.inputText
                                ),
                                modifier = Modifier.weight(0.9f)
                            )
                        }

                        // Point of Sale + Card Count Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ExposedDropdownMenuBox(
                                expanded = posExpanded,
                                onExpandedChange = { posExpanded = !posExpanded },
                                modifier = Modifier.weight(1.2f)
                            ) {
                                OutlinedTextField(
                                    value = state.pointOfSale,
                                    onValueChange = viewModel::onPointOfSaleChange,
                                    label = { Text("نقطة البيع") },
                                    singleLine = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = posExpanded) },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = ext.inputContainer,
                                        unfocusedContainerColor = ext.inputContainer,
                                        focusedTextColor = ext.inputText,
                                        unfocusedTextColor = ext.inputText
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                )
                                ExposedDropdownMenu(
                                    expanded = posExpanded,
                                    onDismissRequest = { posExpanded = false }
                                ) {
                                    posOptions.forEach { pos ->
                                        DropdownMenuItem(
                                            text = { Text(pos) },
                                            onClick = {
                                                viewModel.onPointOfSaleChange(pos)
                                                posExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = state.countInput,
                                onValueChange = viewModel::onCountChange,
                                label = { Text("عدد الكروت") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                shape = RoundedCornerShape(10.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = ext.inputContainer,
                                    unfocusedContainerColor = ext.inputContainer,
                                    focusedTextColor = ext.inputText,
                                    unfocusedTextColor = ext.inputText
                                ),
                                modifier = Modifier.weight(0.9f)
                            )
                        }

                        // Username Length + Password Length Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = state.usernameLengthInput,
                                onValueChange = viewModel::onUsernameLengthChange,
                                label = { Text("طول اسم المستخدم") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                shape = RoundedCornerShape(10.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = ext.inputContainer,
                                    unfocusedContainerColor = ext.inputContainer,
                                    focusedTextColor = ext.inputText,
                                    unfocusedTextColor = ext.inputText
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = state.passwordLengthInput,
                                onValueChange = viewModel::onPasswordLengthChange,
                                label = { Text("طول كلمة السر") },
                                singleLine = true,
                                enabled = !state.usernameOnly,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                shape = RoundedCornerShape(10.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = ext.inputContainer,
                                    unfocusedContainerColor = ext.inputContainer,
                                    focusedTextColor = ext.inputText,
                                    unfocusedTextColor = ext.inputText
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Commission | Suffix (نهاية) | Prefix (بداية)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = state.commissionInput,
                                onValueChange = viewModel::onCommissionChange,
                                label = { Text("العموله") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                shape = RoundedCornerShape(10.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = ext.inputContainer,
                                    unfocusedContainerColor = ext.inputContainer,
                                    focusedTextColor = ext.inputText,
                                    unfocusedTextColor = ext.inputText
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = state.suffixInput,
                                onValueChange = viewModel::onSuffixChange,
                                label = { Text("نهاية") },
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = ext.inputContainer,
                                    unfocusedContainerColor = ext.inputContainer,
                                    focusedTextColor = ext.inputText,
                                    unfocusedTextColor = ext.inputText
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = state.prefixInput,
                                onValueChange = viewModel::onPrefixChange,
                                label = { Text("بداية") },
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = ext.inputContainer,
                                    unfocusedContainerColor = ext.inputContainer,
                                    focusedTextColor = ext.inputText,
                                    unfocusedTextColor = ext.inputText
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Bind First Device Checkbox
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.onBindFirstDeviceChange(!state.bindFirstDevice) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = state.bindFirstDevice,
                                onCheckedChange = viewModel::onBindFirstDeviceChange,
                                colors = CheckboxDefaults.colors(checkedColor = Color(0xFF0288D1))
                            )
                            Text(
                                text = "ربط الكرت مع أول جهاز",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // 4 Action Buttons Row: تصميم الكرت | توليد | اضافة الى السرفر | طباعة
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Button(
                                onClick = onNavigateToDesigner,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ext.royalActionBlue, contentColor = Color.White),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                                modifier = Modifier.weight(1.05f)
                            ) {
                                Text("تصميم الكرت", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                            Button(
                                onClick = viewModel::generateLocalCards,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ext.royalActionBlue, contentColor = Color.White),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                                modifier = Modifier.weight(0.75f)
                            ) {
                                Text("توليد", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                            Button(
                                onClick = viewModel::startAddCardsToServer,
                                enabled = state.generatedCards.isNotEmpty() && !state.isAddingToServer,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (state.additionCompleted) ext.voucherGreen else ext.royalActionBlue,
                                    contentColor = Color.White
                                ),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                                modifier = Modifier.weight(1.25f)
                            ) {
                                Text(
                                    text = if (state.additionCompleted) "تمت الإضافة ✓" else "اضافة الى السرفر",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                            Button(
                                onClick = {
                                    if (viewModel.prepareCardsForA4Print()) {
                                        onNavigateToPrintPreview()
                                    }
                                },
                                enabled = state.generatedCards.isNotEmpty(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ext.royalActionBlue, contentColor = Color.White),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                                modifier = Modifier.weight(0.75f)
                            ) {
                                Text("طباعة", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        }
                    }
                }
            }

            // Generated Cards Summary Strip
            if (state.generatedCards.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "الكروت المولدة: ${state.generatedCards.size} كرت",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = if (state.isAddedToServer) "محفوظة بالسيرفر وجاهزة للطباعة A4" else "مولدة محلياً (لم تُضف للسيرفر بعد)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (state.isAddedToServer) ext.voucherGreen else ext.reportAmberGold
                        )
                    }
                }

                items(state.generatedCards, key = { "${it.id}_${it.username}" }) { card ->
                    RoseGeneratedVoucherCard(card = card)
                }
            }
        }
    }

    // Verified Server Addition Progress Modal (1%..100% + Live Count + Royal Blue Exit on Completion)
    if (state.showServerAdditionDialog) {
        Dialog(
            onDismissRequest = {
                if (!state.isAddingToServer) viewModel.dismissServerAdditionDialog()
            },
            properties = DialogProperties(
                dismissOnBackPress = !state.isAddingToServer,
                dismissOnClickOutside = !state.isAddingToServer
            )
        ) {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(
                                if (state.additionCompleted) CompletedExitRoyalBlue.copy(alpha = 0.14f)
                                else ext.royalActionBlue.copy(alpha = 0.14f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (state.additionCompleted) Icons.Rounded.CheckCircle else Icons.Rounded.CloudUpload,
                            contentDescription = null,
                            tint = if (state.additionCompleted) CompletedExitRoyalBlue else ext.royalActionBlue,
                            modifier = Modifier.size(38.dp)
                        )
                    }

                    Text(
                        text = if (state.additionCompleted) "اكتملت الإضافة إلى السيرفر بنجاح" else "جاري الإضافة إلى السيرفر...",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    LinearProgressIndicator(
                        progress = { (state.additionProgressPercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        color = if (state.additionCompleted) CompletedExitRoyalBlue else ext.royalActionBlue,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        strokeCap = StrokeCap.Round
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "تمت إضافة ${state.addedCardsCount} من أصل ${state.targetAdditionCount} كرت",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${state.additionProgressPercent}%",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = CompletedExitRoyalBlue
                        )
                    }

                    if (state.additionCompleted && state.skippedDuplicatesCount > 0) {
                        Text(
                            text = "تم تجاهل ${state.skippedDuplicatesCount} كرت مكرر تلقائياً.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Exit Button turns vivid Royal Blue (#1976D2) upon completion!
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = viewModel::dismissServerAdditionDialog,
                            enabled = !state.isAddingToServer,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.additionCompleted) CompletedExitRoyalBlue else Color(0xFF94A3B8),
                                contentColor = Color.White,
                                disabledContainerColor = Color(0xFFCBD5E1),
                                disabledContentColor = Color(0xFF64748B)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ExitToApp,
                                contentDescription = "خروج",
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("خروج", fontWeight = FontWeight.ExtraBold)
                        }

                        if (state.additionCompleted) {
                            Button(
                                onClick = {
                                    viewModel.dismissServerAdditionDialog()
                                    if (viewModel.prepareCardsForA4Print()) {
                                        onNavigateToPrintPreview()
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ext.voucherGreen,
                                    contentColor = Color.White
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Text("معاينة A4", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoseGeneratedVoucherCard(card: CardEntity) {
    val ext = AlkamelTheme.extendedColors
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ext.voucherRose),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
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
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AccountBalanceWallet,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1.2f)
                ) {
                    Text(
                        text = "اسم المستخدم",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.90f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
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
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.90f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = card.password.ifBlank { "-" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.45f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "نقطة البيع : ${card.pointOfSale}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = "الباقة : ${card.packageName}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}
