package com.alkamel.cardprinter.ui.designer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.alkamel.cardprinter.ui.components.AlkamelTopHeader
import com.alkamel.cardprinter.ui.components.CardDesignPreviewBox
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
    // Exclusively User Manager packages synced from MikroTik User Manager
    val userManagerPackageChoices = remember(state.packages) {
        (listOf("الكل") + state.packages.map { it.name.trim() }.filter { it.isNotBlank() }).distinct()
    }
    val hasBackgroundImage = !state.activeTemplate.backgroundImagePath.isNullOrBlank()

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

            // 1. Interactive Single Card Preview + Background Image + A4 Full Preview + Remove Image
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
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
                        selectedElement = state.selectedElement,
                        isInteractive = true,
                        onElementDragged = viewModel::onElementDragged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(ratio)
                    )

                    OutlinedButton(
                        onClick = { imagePicker.launch("image/*") },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("اختيار صورة الخلفية", fontWeight = FontWeight.SemiBold)
                    }

                    // «معاينة» button right next to «إزالة الصورة»
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = viewModel::openFullA4Preview,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ext.royalActionBlue,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("معاينة", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = viewModel::clearBackgroundImage,
                            enabled = hasBackgroundImage,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "إزالة الصورة",
                                color = if (hasBackgroundImage) ext.destructivePink else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // 2. Text Color & Font Size + Visible Elements DIRECTLY ABOVE Template & A4 Size Settings
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "لون النص وحجم الخط والعناصر الظاهرة",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Text("لون النص:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        colorSwatches.forEach { hex ->
                            val selected = state.activeTemplate.textColorHex.equals(hex, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = state.activeTemplate.showQrCode, onCheckedChange = viewModel::onToggleShowQrCode)
                        Text("إظهار رمز QR")
                    }
                }
            }

            // 3. Template Settings & A4 Sheet Dimensions (with User Manager packages & live A4 capacity)
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "إعدادات القالب ومقاسات ورقة A4",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
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
                            value = if (state.activeTemplate.packageName == "الكل") {
                                "الكل (جميع باقات يوزر منجر)"
                            } else {
                                state.activeTemplate.packageName
                            },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("ربط القالب بباقة يوزر منجر") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = pkgMenuExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(
                            expanded = pkgMenuExpanded,
                            onDismissRequest = { pkgMenuExpanded = false }
                        ) {
                            userManagerPackageChoices.forEach { pkgName ->
                                val display = if (pkgName == "الكل") "الكل (جميع باقات يوزر منجر)" else pkgName
                                DropdownMenuItem(
                                    text = { Text(display) },
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

                    // Instant dynamic A4 sheet card count indicator
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = ext.voucherGreen.copy(alpha = 0.12f)),
                        border = BorderStroke(1.dp, ext.voucherGreen.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GridView,
                                contentDescription = null,
                                tint = ext.voucherGreen,
                                modifier = Modifier.size(26.dp)
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "سيتم إضافة وطباعة ${state.cardsPerA4Page} كرت في صفحة A4 الواحدة",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = ext.voucherGreen,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    text = "التوزيع على A4: ${state.activeTemplate.columnsPerPage} أعمدة × ${state.activeTemplate.rowsPerPage} صفوف • مقاس الكرت: ${state.activeTemplate.cardWidth.toInt()}×${state.activeTemplate.cardHeight.toInt()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // 4. Save & Delete Buttons
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

    // Full A4 Page Preview Dialog
    if (state.showA4PreviewDialog) {
        Dialog(
            onDismissRequest = viewModel::closeFullA4Preview,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .padding(vertical = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "معاينة صفحة A4 الكاملة",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "${state.cardsPerA4Page} كرت في الصفحة (${state.activeTemplate.columnsPerPage} أعمدة × ${state.activeTemplate.rowsPerPage} صفوف)",
                                style = MaterialTheme.typography.labelMedium,
                                color = ext.voucherGreen,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        IconButton(onClick = viewModel::closeFullA4Preview) {
                            Icon(Icons.Rounded.Close, contentDescription = "إغلاق")
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(210f / 297f)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.5.dp, Color(0xFFCBD5E1), RoundedCornerShape(10.dp))
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        val bmp = state.a4PreviewBitmap
                        if (state.isRenderingA4Preview || bmp == null) {
                            CircularProgressIndicator(color = ext.royalActionBlue)
                        } else {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "معاينة ورقة A4",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    Button(
                        onClick = viewModel::closeFullA4Preview,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ext.royalActionBlue,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("إغلاق المعاينة", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
