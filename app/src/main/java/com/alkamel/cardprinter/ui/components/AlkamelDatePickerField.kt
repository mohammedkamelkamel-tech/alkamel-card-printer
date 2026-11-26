package com.alkamel.cardprinter.ui.components

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.alkamel.cardprinter.ui.theme.AlkamelTheme
import java.util.Calendar
import java.util.Locale

/**
 * Clickable date field that opens the native Android [DatePickerDialog] directly
 * instead of requiring manual keyboard entry, formatting picked dates as `yyyy-MM-dd` (Locale.US).
 */
@Composable
fun AlkamelDatePickerField(
    value: String,
    onDateSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    allowClear: Boolean = false,
    onClear: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val ext = AlkamelTheme.extendedColors

    fun openDatePicker() {
        val cal = Calendar.getInstance()
        val parts = value.trim().split("-")
        if (parts.size == 3) {
            val y = parts[0].toIntOrNull()
            val m = parts[1].toIntOrNull()
            val d = parts[2].toIntOrNull()
            if (y != null && m != null && d != null && m in 1..12 && d in 1..31) {
                cal.set(Calendar.YEAR, y)
                cal.set(Calendar.MONTH, m - 1)
                cal.set(Calendar.DAY_OF_MONTH, d)
            }
        }
        DatePickerDialog(
            context,
            { _, year, monthZeroBased, dayOfMonth ->
                val formatted = String.format(
                    Locale.US,
                    "%04d-%02d-%02d",
                    year,
                    monthZeroBased + 1,
                    dayOfMonth
                )
                onDateSelected(formatted)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    Box(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = true,
            singleLine = true,
            label = label?.let { { Text(it) } },
            placeholder = placeholder?.let { { Text(it) } },
            shape = RoundedCornerShape(12.dp),
            trailingIcon = {
                Row {
                    if (allowClear && value.isNotBlank() && onClear != null) {
                        IconButton(onClick = onClear, modifier = Modifier.size(36.dp)) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "مسح التاريخ",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    IconButton(onClick = ::openDatePicker, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.CalendarMonth,
                            contentDescription = "اختيار التاريخ",
                            tint = ext.royalActionBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = ext.inputContainer,
                unfocusedContainerColor = ext.inputContainer,
                focusedTextColor = ext.inputText,
                unfocusedTextColor = ext.inputText
            ),
            modifier = Modifier.fillMaxWidth()
        )
        // Transparent clickable overlay over the text area so tapping the field opens DatePickerDialog
        // while keeping trailing clear / calendar icon buttons directly clickable.
        val interactionSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(end = if (allowClear && value.isNotBlank() && onClear != null) 80.dp else 44.dp)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = ::openDatePicker
                )
        )
    }
}
