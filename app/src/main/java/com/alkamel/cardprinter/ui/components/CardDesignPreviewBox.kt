package com.alkamel.cardprinter.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.alkamel.cardprinter.data.local.entity.CardTemplateEntity
import com.alkamel.cardprinter.data.printing.CardLayoutMetrics
import java.io.File
import kotlin.math.roundToInt

enum class DesignerElement(val arabicLabel: String) {
    USERNAME("اسم المستخدم"),
    PASSWORD("كلمة السر"),
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
    selectedElement: DesignerElement?,
    isInteractive: Boolean,
    onElementDragged: (DesignerElement, Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val safeTemplate = remember(template) { CardLayoutMetrics.sanitize(template) }
    val context = LocalContext.current
    val density = LocalDensity.current
    val latestOnDragged by rememberUpdatedState(onElementDragged)

    val textColor = remember(safeTemplate.textColorHex) {
        parseHexColorSafe(safeTemplate.textColorHex, Color.White)
    }
    val bgFile = remember(safeTemplate.backgroundImagePath) {
        safeTemplate.backgroundImagePath
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.exists() && it.length() > 0L }
    }

    // Force physical LTR coordinate space inside the card surface so (0..1, 0..1)
    // matches Android Canvas (CardBitmapRenderer / A4 PDF) with zero RTL horizontal inversion.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
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

            // Exact pixel text size identical to CardBitmapRenderer, immune to system fontScale drift
            val pxPerSp = (density.density * density.fontScale).coerceAtLeast(0.5f)
            val usernamePx = CardLayoutMetrics.computeUsernameTextPx(safeTemplate.fontSizeSp, widthPx, heightPx)
            val passwordPx = CardLayoutMetrics.computePasswordTextPx(safeTemplate.fontSizeSp, widthPx, heightPx)
            val usernameSp = (usernamePx / pxPerSp).sp
            val passwordSp = (passwordPx / pxPerSp).sp

            if (bgFile != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(bgFile)
                        .crossfade(false)
                        .build(),
                    contentDescription = "خلفية القالب",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                val titleSp = ((heightPx * 0.14f).coerceIn(7f, 24f) / pxPerSp).sp
                Text(
                    text = "الكامل نت • ${safeTemplate.packageName}",
                    color = Color.White.copy(alpha = 0.88f),
                    fontSize = titleSp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            if (safeTemplate.showUsername) {
                DraggableOverlayText(
                    element = DesignerElement.USERNAME,
                    text = sampleUsername,
                    relX = safeTemplate.usernameX,
                    relY = safeTemplate.usernameY,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    color = textColor,
                    fontSizeSp = usernameSp,
                    isSelected = isInteractive && selectedElement == DesignerElement.USERNAME,
                    isInteractive = isInteractive,
                    onDrag = { dxPx, dyPx ->
                        if (widthPx > 1f && heightPx > 1f) {
                            latestOnDragged(DesignerElement.USERNAME, dxPx / widthPx, dyPx / heightPx)
                        }
                    }
                )
            }

            if (safeTemplate.showPassword && samplePassword.isNotBlank()) {
                DraggableOverlayText(
                    element = DesignerElement.PASSWORD,
                    text = samplePassword,
                    relX = safeTemplate.passwordX,
                    relY = safeTemplate.passwordY,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    color = textColor,
                    fontSizeSp = passwordSp,
                    isSelected = isInteractive && selectedElement == DesignerElement.PASSWORD,
                    isInteractive = isInteractive,
                    onDrag = { dxPx, dyPx ->
                        if (widthPx > 1f && heightPx > 1f) {
                            latestOnDragged(DesignerElement.PASSWORD, dxPx / widthPx, dyPx / heightPx)
                        }
                    }
                )
            }

            if (safeTemplate.showQrCode) {
                val qrSizePx = CardLayoutMetrics.computeQrSizePx(widthPx, heightPx)
                val qrDp = with(density) { qrSizePx.toDp() }
                val qrContent = if (samplePassword.isNotBlank()) "U:$sampleUsername|P:$samplePassword" else sampleUsername
                val qrBmp = remember(qrContent) { QrCodeBitmapGenerator.generateQrBitmap(qrContent, 140) }
                if (qrBmp != null) {
                    val maxLeft = (widthPx - qrSizePx).coerceAtLeast(0f)
                    val maxTop = (heightPx - qrSizePx).coerceAtLeast(0f)
                    val leftPx = (safeTemplate.qrX * widthPx - qrSizePx / 2f).coerceIn(0f, maxLeft).roundToInt()
                    val topPx = (safeTemplate.qrY * heightPx - qrSizePx / 2f).coerceIn(0f, maxTop).roundToInt()
                    val latestWidth by rememberUpdatedState(widthPx)
                    val latestHeight by rememberUpdatedState(heightPx)

                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .absoluteOffset { IntOffset(leftPx, topPx) }
                            .size(qrDp)
                            .background(Color.White, RoundedCornerShape(3.dp))
                            .then(
                                if (isInteractive && selectedElement == DesignerElement.QR_CODE) {
                                    Modifier.border(1.5.dp, Color(0xFFFFCA28), RoundedCornerShape(3.dp))
                                } else Modifier
                            )
                            .then(
                                if (isInteractive) {
                                    Modifier.pointerInput(DesignerElement.QR_CODE) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            if (latestWidth > 1f && latestHeight > 1f) {
                                                latestOnDragged(
                                                    DesignerElement.QR_CODE,
                                                    dragAmount.x / latestWidth,
                                                    dragAmount.y / latestHeight
                                                )
                                            }
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
}

@Composable
private fun DraggableOverlayText(
    element: DesignerElement,
    text: String,
    relX: Float,
    relY: Float,
    widthPx: Float,
    heightPx: Float,
    color: Color,
    fontSizeSp: androidx.compose.ui.unit.TextUnit,
    isSelected: Boolean,
    isInteractive: Boolean,
    onDrag: (Float, Float) -> Unit
) {
    var measuredSize by remember { mutableStateOf(IntSize.Zero) }
    val latestOnDrag by rememberUpdatedState(onDrag)

    val centerX = relX * widthPx
    val centerY = relY * heightPx
    val maxLeft = (widthPx - measuredSize.width).coerceAtLeast(0f)
    val maxTop = (heightPx - measuredSize.height).coerceAtLeast(0f)
    val leftPx = (centerX - measuredSize.width / 2f).coerceIn(0f, maxLeft).roundToInt()
    val topPx = (centerY - measuredSize.height / 2f).coerceIn(0f, maxTop).roundToInt()

    Box(
        modifier = Modifier
            .absoluteOffset { IntOffset(leftPx, topPx) }
            .onSizeChanged { measuredSize = it }
            .then(
                if (isSelected) {
                    Modifier
                        .background(Color.Black.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
                        .border(1.2.dp, Color(0xFFFFCA28), RoundedCornerShape(4.dp))
                } else Modifier
            )
            .then(
                if (isInteractive) {
                    Modifier.pointerInput(element) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            latestOnDrag(dragAmount.x, dragAmount.y)
                        }
                    }
                } else Modifier
            )
            .padding(horizontal = if (isInteractive) 3.dp else 1.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = color,
            fontSize = fontSizeSp,
            lineHeight = fontSizeSp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
    }
}
