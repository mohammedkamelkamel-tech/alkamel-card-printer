package com.alkamel.cardprinter.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.alkamel.cardprinter.data.local.entity.CardTemplateEntity
import java.io.File
import kotlin.math.roundToInt

enum class DesignerElement(val arabicLabel: String) {
    USERNAME("اسم المستخدم"),
    PASSWORD("كلمة السر"),
    POINT_OF_SALE("نقطة البيع"),
    QR_CODE("رمز QR")
}

fun parseHexColorSafe(hex: String, fallback: Color = Color.White): Color {
    return runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)
}

@Composable
fun CardDesignPreviewBox(
    template: CardTemplateEntity,
    sampleUsername: String,
    samplePassword: String,
    samplePointOfSale: String,
    selectedElement: DesignerElement?,
    isInteractive: Boolean,
    onElementDragged: (DesignerElement, Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val textColor = remember(template.textColorHex) { parseHexColorSafe(template.textColorHex, Color.White) }
    val bgFile = remember(template.backgroundImagePath) {
        template.backgroundImagePath?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }
    }

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF0F2342), Color(0xFF1D4E89))
                )
            )
    ) {
        val widthPx = with(density) { maxWidth.toPx().coerceAtLeast(1f) }
        val heightPx = with(density) { maxHeight.toPx().coerceAtLeast(1f) }
        val fontScale = (widthPx / 620f).coerceIn(0.35f, 1.35f)
        val scaledFontSp = (template.fontSizeSp * fontScale).coerceIn(7f, 26f)

        if (bgFile != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(bgFile).crossfade(false).build(),
                contentDescription = "خلفية القالب",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = "الكامل نت • ${template.packageName}",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = (scaledFontSp * 0.65f).sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }

        if (template.showUsername) {
            DraggableOverlayText(
                text = sampleUsername,
                relX = template.usernameX,
                relY = template.usernameY,
                widthPx = widthPx,
                heightPx = heightPx,
                color = textColor,
                fontSizeSp = scaledFontSp,
                isSelected = selectedElement == DesignerElement.USERNAME,
                isInteractive = isInteractive,
                onDrag = { dx, dy -> onElementDragged(DesignerElement.USERNAME, dx / widthPx, dy / heightPx) }
            )
        }

        if (template.showPassword && samplePassword.isNotBlank()) {
            DraggableOverlayText(
                text = samplePassword,
                relX = template.passwordX,
                relY = template.passwordY,
                widthPx = widthPx,
                heightPx = heightPx,
                color = textColor,
                fontSizeSp = (scaledFontSp * 0.85f).coerceAtLeast(6.5f),
                isSelected = selectedElement == DesignerElement.PASSWORD,
                isInteractive = isInteractive,
                onDrag = { dx, dy -> onElementDragged(DesignerElement.PASSWORD, dx / widthPx, dy / heightPx) }
            )
        }

        if (template.showPointOfSale && samplePointOfSale.isNotBlank()) {
            DraggableOverlayText(
                text = samplePointOfSale,
                relX = template.pointOfSaleX,
                relY = template.pointOfSaleY,
                widthPx = widthPx,
                heightPx = heightPx,
                color = textColor,
                fontSizeSp = (scaledFontSp * 0.7f).coerceAtLeast(6f),
                isSelected = selectedElement == DesignerElement.POINT_OF_SALE,
                isInteractive = isInteractive,
                onDrag = { dx, dy -> onElementDragged(DesignerElement.POINT_OF_SALE, dx / widthPx, dy / heightPx) }
            )
        }

        if (template.showQrCode) {
            val qrDp = (minOf(maxWidth, maxHeight) * 0.44f).coerceIn(18.dp, 72.dp)
            val qrContent = if (samplePassword.isNotBlank()) "U:$sampleUsername|P:$samplePassword" else sampleUsername
            val qrBmp = remember(qrContent) { QrCodeBitmapGenerator.generateQrBitmap(qrContent, 120) }
            if (qrBmp != null) {
                val qrSizePx = with(density) { qrDp.toPx() }
                val x = (template.qrX.coerceIn(0.08f, 0.92f) * widthPx - qrSizePx / 2f).roundToInt()
                val y = (template.qrY.coerceIn(0.12f, 0.88f) * heightPx - qrSizePx / 2f).roundToInt()
                Box(
                    modifier = Modifier
                        .offset { IntOffset(x, y) }
                        .size(qrDp)
                        .background(Color.White, RoundedCornerShape(3.dp))
                        .then(
                            if (selectedElement == DesignerElement.QR_CODE) {
                                Modifier.border(1.5.dp, Color(0xFFFFCA28), RoundedCornerShape(3.dp))
                            } else Modifier
                        )
                        .then(
                            if (isInteractive) {
                                Modifier.pointerInput(DesignerElement.QR_CODE) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        onElementDragged(DesignerElement.QR_CODE, dragAmount.x / widthPx, dragAmount.y / heightPx)
                                    }
                                }
                            } else Modifier
                        )
                        .padding(1.dp)
                ) {
                    Image(
                        bitmap = qrBmp.asImageBitmap(),
                        contentDescription = "QR",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun DraggableOverlayText(
    text: String,
    relX: Float,
    relY: Float,
    widthPx: Float,
    heightPx: Float,
    color: Color,
    fontSizeSp: Float,
    isSelected: Boolean,
    isInteractive: Boolean,
    onDrag: (Float, Float) -> Unit
) {
    val approxHalfW = (text.length * fontSizeSp * 0.34f).coerceIn(12f, widthPx * 0.45f)
    val x = (relX.coerceIn(0.05f, 0.95f) * widthPx - approxHalfW).roundToInt()
    val y = (relY.coerceIn(0.10f, 0.90f) * heightPx - fontSizeSp).roundToInt()

    Box(
        modifier = Modifier
            .offset { IntOffset(x, y) }
            .then(
                if (isSelected) {
                    Modifier.border(1.dp, Color(0xFFFFCA28), RoundedCornerShape(4.dp))
                } else Modifier
            )
            .then(
                if (isInteractive) {
                    Modifier.pointerInput(text) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.x, dragAmount.y)
                        }
                    }
                } else Modifier
            )
            .padding(horizontal = 2.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = fontSizeSp.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}
