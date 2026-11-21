package com.alkamel.cardprinter.ui.designer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.alkamel.cardprinter.ui.components.AlkamelTopHeader
import com.alkamel.cardprinter.ui.components.CardDesignPreviewBox
import com.alkamel.cardprinter.ui.components.DesignerElement
import com.alkamel.cardprinter.ui.components.parseHexColorSafe
import com.alkamel.cardprinter.ui.theme.AlkamelTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardDesignerScreen(
    viewModel: CardDesignerViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val ext = AlkamelTheme.extendedColors
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var pkgMenuExpanded by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.copyPickedBackgroundImage(context, uri)
        }
    }

    LaunchedEffect(state.statusMessage, state.errorMessage) {
        val msg = state.errorMessage ?: state.statusMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessages()
        }
    }

    val colorSwatches = listOf("#FFFFFF", "#0F172A", "#FFEB3B", "#FF5252", "#29B6F6", "#69F0AE")
    val packageChoices = remember(state.packages) {
        (listOf("الكل") + state.packages.map { it.name }).distinct()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AlkamelTopHeader(
                title = "تصميم الكرت (A4)",
                onNavigateBack = onNavigateBack,
                trailingAction = {
                    IconButton(onClick = viewModel::createNewBlankTemplate) {
                        Icon(Icons.Rounded.Add, contentDescription = "قالب جديد", tint = Color.White)
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Saved Templates Selector Row
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
                            label = { Text("${tpl.name} (${tpl.columnsPerPage}×${tpl.rowsPerPage})") }
                        )
                    }
                }
            }

            // Live Interactive Preview Box
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "معاينة تفاعلية — اسحب النصوص أو رمز QR لتحديد موقعها",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = ext.royalActionBlue
                    )
                    val ratio = (state.activeTemplate.cardWidth / state.activeTemplate.cardHeight.coerceAtLeast(25f))
                        .coerceIn(1.4f, 4.2f)
                    CardDesignPreviewBox(
                        template = state.activeTemplate,
                        sampleUsername = "15650109767",
                        samplePassword = "4821",
                        samplePointOfSale = "ether3",
                        selectedElement = state.selectedElement,
                        isInteractive = true,
                        onElementDragged = viewModel::onElementDragged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(ratio)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { imagePicker.launch("image/*") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("اختيار صورة الخلفية")
                        }
                        if (!state.activeTemplate.backgroundImagePath.isNullOrBlank()) {
                            OutlinedButton(onClick = viewModel::clearBackgroundImage) {
                                Text("إزالة الصورة", color = ext.destructivePink)
                            }
                        }
                    }
                }
            }

            // Template Info & A4 Grid Dimensions
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("إعدادات القالب ومقاسات ورقة A4", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = state.activeTemplate.name,
                        onValueChange = viewModel::onTemplateNameChange,
                        label = { Text("اسم القالب") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    ExposedDropdownMenuBox(
                        expanded = pkgMenuExpanded,
                        onExpandedChange = { pkgMenuExpanded = !pkgMenuExpanded }
                    ) {
                        OutlinedTextField(
                            value = state.activeTemplate.packageName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("ربط القالب بباقة") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = pkgMenuExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(
                            expanded = pkgMenuExpanded,
                            onDismissRequest = { pkgMenuExpanded = false }
                        ) {
                            packageChoices.forEach { pkgName ->
                                DropdownMenuItem(
                                    text = { Text(pkgName) },
                                    onClick = {
                                        viewModel.onPackageNameChange(pkgName)
                                        pkgMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = state.widthInput,
                            onValueChange = viewModel::onWidthChange,
                            label = { Text("عرض الكرت") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = state.heightInput,
                            onValueChange = viewModel::onHeightChange,
                            label = { Text("ارتفاع الكرت") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = state.columnsInput,
                            onValueChange = viewModel::onColumnsChange,
                            label = { Text("الأعمدة (1-6)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = state.rowsInput,
                            onValueChange = viewModel::onRowsChange,
                            label = { Text("الصفوف (1-30)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text(
                        text = "إجمالي الكروت في الصفحة الواحدة (A4): ${state.activeTemplate.columnsPerPage * state.activeTemplate.rowsPerPage} كرت",
                        style = MaterialTheme.typography.labelLarge,
                        color = ext.voucherGreen,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Show Elements, Color & Font Size
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("العناصر الظاهرة واللون وحجم الخط", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Checkbox(checked = state.activeTemplate.showUsername, onCheckedChange = viewModel::onToggleShowUsername)
                            Text("اسم المستخدم")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Checkbox(checked = state.activeTemplate.showPassword, onCheckedChange = viewModel::onToggleShowPassword)
                            Text("كلمة السر")
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Checkbox(checked = state.activeTemplate.showPointOfSale, onCheckedChange = viewModel::onToggleShowPointOfSale)
                            Text("نقطة البيع")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Checkbox(checked = state.activeTemplate.showQrCode, onCheckedChange = viewModel::onToggleShowQrCode)
                            Text("رمز QR")
                        }
                    }

                    Text("لون النص:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        colorSwatches.forEach { hex ->
                            val selected = state.activeTemplate.textColorHex.equals(hex, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(parseHexColorSafe(hex, Color.White))
                                    .border(
                                        width = if (selected) 3.dp else 1.dp,
                                        color = if (selected) ext.royalActionBlue else Color.Gray,
                                        shape = CircleShape
                                    )
                                    .clickable { viewModel.onTextColorSelected(hex) }
                            )
                        }
                    }

                    Text(
                        text = "حجم الخط: ${state.activeTemplate.fontSizeSp.toInt()} sp",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Slider(
                        value = state.activeTemplate.fontSizeSp,
                        onValueChange = viewModel::onFontSizeChange,
                        valueRange = 10f..28f
                    )
                }
            }

            // Save & Delete Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = viewModel::saveActiveTemplate,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ext.royalActionBlue, contentColor = Color.White),
                    modifier = Modifier
                        .weight(1.3f)
                        .height(50.dp)
                ) {
                    Icon(Icons.Rounded.Save, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("حفظ القالب", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = viewModel::deleteActiveTemplate,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ext.destructivePink, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier
                        .weight(0.7f)
                        .height(50.dp)
                ) {
                    Icon(Icons.Rounded.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("حذف", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
