package com.alkamel.cardprinter.ui.components

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.alkamel.cardprinter.data.local.entity.CardEntity
import com.alkamel.cardprinter.data.local.entity.CardTemplateEntity
import com.alkamel.cardprinter.data.printing.CardBitmapRenderer
import com.alkamel.cardprinter.data.printing.CardLayoutMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class DesignerElement(val arabicLabel: String) {
    USERNAME("رقم الكرت"),
    PASSWORD("كلمة السر"),
    QR_CODE("رمز QR")
}

fun parseHexColorSafe(hex: String, fallback: Color = Color.White): Color {
    return runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)
}

/**
 * Interactive single-card preview rendered directly by [CardBitmapRenderer.drawCardOnCanvas]
 * so the designer preview, Full A4 Preview, Print Preview, Direct Print, and Exported PDF
 * all execute the exact same single rendering engine (100% pixel-locked WYSIWYG).
 */
@Composable
fun CardDesignPreviewBox(
    template: CardTemplateEntity,
    sampleUsername: String,
    samplePassword: String,
    selectedElement: DesignerElement?,
    isInteractive: Boolean,
    onElementDragged: (DesignerElement, Float, Float) -> Unit,
    onElementSelected: (DesignerElement) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val safeTemplate = remember(template) { CardLayoutMetrics.sanitize(template) }
    val latestTemplate by rememberUpdatedState(safeTemplate)
    val latestUsername by rememberUpdatedState(sampleUsername)
    val latestPassword by rememberUpdatedState(samplePassword)
    val latestSelected by rememberUpdatedState(selectedElement)
    val latestOnDragged by rememberUpdatedState(onElementDragged)
    val latestOnSelected by rememberUpdatedState(onElementSelected)

    val bgState by produceState<Pair<String?, Bitmap?>>(
        initialValue = null to null,
        key1 = safeTemplate.backgroundImagePath
    ) {
        val targetPath = safeTemplate.backgroundImagePath
        val decoded = if (targetPath.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                CardBitmapRenderer.decodeBackgroundBitmap(safeTemplate)
            }
        }
        value = targetPath to decoded
    }
    val activeBgBitmap = if (bgState.first == safeTemplate.backgroundImagePath) bgState.second else null

    val sampleCard = remember(sampleUsername, samplePassword, safeTemplate.packageName) {
        CardEntity(
            username = sampleUsername,
            password = samplePassword,
            packageName = safeTemplate.packageName
        )
    }

    // Physical LTR coordinate space ensures (0..1, 0..1) matches PDF/Print Canvas identically
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(4.dp))
                .then(
                    if (isInteractive) {
                        Modifier
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    val hit = CardBitmapRenderer.hitTestElement(
                                        touchX = offset.x,
                                        touchY = offset.y,
                                        sampleUsername = latestUsername,
                                        samplePassword = latestPassword,
                                        template = latestTemplate,
                                        cardW = size.width.toFloat(),
                                        cardH = size.height.toFloat()
                                    )
                                    if (hit != null) {
                                        latestOnSelected(hit)
                                    }
                                }
                            }
                            .pointerInput(Unit) {
                                var activeDragElement: DesignerElement = DesignerElement.USERNAME
                                detectDragGestures(
                                    onDragStart = { startOffset ->
                                        val hit = CardBitmapRenderer.hitTestElement(
                                            touchX = startOffset.x,
                                            touchY = startOffset.y,
                                            sampleUsername = latestUsername,
                                            samplePassword = latestPassword,
                                            template = latestTemplate,
                                            cardW = size.width.toFloat(),
                                            cardH = size.height.toFloat()
                                        )
                                        activeDragElement = hit ?: latestSelected ?: DesignerElement.USERNAME
                                        latestOnSelected(activeDragElement)
                                    }
                                ) { change, dragAmount ->
                                    change.consume()
                                    val w = size.width.toFloat()
                                    val h = size.height.toFloat()
                                    if (w > 1f && h > 1f) {
                                        latestOnDragged(activeDragElement, dragAmount.x / w, dragAmount.y / h)
                                    }
                                }
                            }
                    } else Modifier
                )
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawIntoCanvas { canvas ->
                    CardBitmapRenderer.drawCardOnCanvas(
                        canvas = canvas.nativeCanvas,
                        dest = RectF(0f, 0f, size.width, size.height),
                        card = sampleCard,
                        template = safeTemplate,
                        preloadedBackground = activeBgBitmap,
                        selectedElementHighlight = if (isInteractive) selectedElement else null
                    )
                }
            }
        }
    }
}
