package com.alkamel.cardprinter.data.printing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.alkamel.cardprinter.data.local.entity.CardEntity
import com.alkamel.cardprinter.data.local.entity.CardTemplateEntity
import com.alkamel.cardprinter.ui.components.QrCodeBitmapGenerator
import java.io.File
import kotlin.math.roundToInt

object CardBitmapRenderer {

    /**
     * Decodes a template's independent background image once so printing a full A4 page
     * (up to 100+ cards) reuses the same decoded Bitmap without repeated disk I/O.
     */
    fun decodeBackgroundBitmap(template: CardTemplateEntity): Bitmap? {
        val path = template.backgroundImagePath?.takeIf { it.isNotBlank() } ?: return null
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    fun renderCardBitmap(
        card: CardEntity,
        template: CardTemplateEntity,
        widthPx: Int = 609,
        heightPx: Int = (widthPx * (template.cardHeight / template.cardWidth.coerceAtLeast(1f)))
            .roundToInt().coerceAtLeast(140),
        preloadedBackground: Bitmap? = null
    ): Bitmap {
        val safeTemplate = CardLayoutMetrics.sanitize(template)
        val safeW = widthPx.coerceAtLeast(60)
        val safeH = heightPx.coerceAtLeast(24)

        val bitmap = Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        var hasCustomBg = false
        val ownDecoded = if (preloadedBackground != null && !preloadedBackground.isRecycled) {
            null
        } else {
            decodeBackgroundBitmap(safeTemplate)
        }
        val activeBg = preloadedBackground?.takeIf { !it.isRecycled } ?: ownDecoded

        if (activeBg != null) {
            // Draw full image bounds (matches ContentScale.FillBounds in CardDesignPreviewBox)
            // so (relX, relY) ALWAYS maps to the exact pixel on the card artwork with zero cropping drift.
            val src = Rect(0, 0, activeBg.width, activeBg.height)
            val dst = Rect(0, 0, safeW, safeH)
            canvas.drawBitmap(activeBg, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            hasCustomBg = true
        }
        ownDecoded?.recycle()

        if (!hasCustomBg) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, safeW.toFloat(), safeH.toFloat(),
                    intArrayOf(Color.parseColor("#0F2342"), Color.parseColor("#1D4E89")),
                    null, Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(RectF(0f, 0f, safeW.toFloat(), safeH.toFloat()), 8f, 8f, bgPaint)

            val barPaint = Paint().apply { color = Color.argb(40, 255, 255, 255) }
            canvas.drawRect(0f, 0f, safeW.toFloat(), safeH * 0.24f, barPaint)

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = (safeH * 0.14f).coerceIn(10f, 24f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.RIGHT
            }
            val headerLabel = if (card.packageName.isNotBlank()) "الكامل نت • ${card.packageName}" else "الكامل نت"
            canvas.drawText(headerLabel, safeW - 10f, safeH * 0.17f, titlePaint)
        }

        val textColor = runCatching { Color.parseColor(safeTemplate.textColorHex) }
            .getOrDefault(if (hasCustomBg) Color.BLACK else Color.WHITE)

        val usernameFontPx = CardLayoutMetrics.computeUsernameTextPx(
            fontSizeSp = safeTemplate.fontSizeSp,
            cardWidthPx = safeW.toFloat(),
            cardHeightPx = safeH.toFloat()
        )
        val passwordFontPx = CardLayoutMetrics.computePasswordTextPx(
            fontSizeSp = safeTemplate.fontSizeSp,
            cardWidthPx = safeW.toFloat(),
            cardHeightPx = safeH.toFloat()
        )

        if (safeTemplate.showUsername && card.username.isNotBlank()) {
            drawCenteredTextInsideCard(
                canvas = canvas,
                text = card.username,
                relX = safeTemplate.usernameX,
                relY = safeTemplate.usernameY,
                cardW = safeW.toFloat(),
                cardH = safeH.toFloat(),
                color = textColor,
                targetTextSizePx = usernameFontPx
            )
        }

        if (safeTemplate.showPassword && card.password.isNotBlank()) {
            drawCenteredTextInsideCard(
                canvas = canvas,
                text = card.password,
                relX = safeTemplate.passwordX,
                relY = safeTemplate.passwordY,
                cardW = safeW.toFloat(),
                cardH = safeH.toFloat(),
                color = textColor,
                targetTextSizePx = passwordFontPx
            )
        }

        if (safeTemplate.showQrCode) {
            val qrSize = CardLayoutMetrics.computeQrSizePx(safeW.toFloat(), safeH.toFloat())
                .roundToInt()
                .coerceIn(20, minOf(safeW, safeH) - 4)
            val data = if (card.password.isNotBlank()) "U:${card.username}|P:${card.password}" else card.username
            QrCodeBitmapGenerator.generateQrBitmap(data, qrSize)?.let { qr ->
                val maxLeft = (safeW - qrSize).coerceAtLeast(0).toFloat()
                val maxTop = (safeH - qrSize).coerceAtLeast(0).toFloat()
                val left = (safeTemplate.qrX * safeW - qrSize / 2f).coerceIn(0f, maxLeft)
                val top = (safeTemplate.qrY * safeH - qrSize / 2f).coerceIn(0f, maxTop)
                canvas.drawRect(
                    (left - 2f).coerceAtLeast(0f),
                    (top - 2f).coerceAtLeast(0f),
                    (left + qrSize + 2f).coerceAtMost(safeW.toFloat()),
                    (top + qrSize + 2f).coerceAtMost(safeH.toFloat()),
                    Paint().apply { color = Color.WHITE }
                )
                canvas.drawBitmap(qr, left, top, null)
                qr.recycle()
            }
        }
        return bitmap
    }

    /**
     * Draws text centered horizontally AND vertically at (relX * cardW, relY * cardH),
     * matching Compose's DraggableOverlayText center anchor pixel-for-pixel, while guaranteeing
     * the text is never clipped outside the card rectangle.
     */
    private fun drawCenteredTextInsideCard(
        canvas: Canvas,
        text: String,
        relX: Float,
        relY: Float,
        cardW: Float,
        cardH: Float,
        color: Int,
        targetTextSizePx: Float
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = targetTextSizePx
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        // Auto-fit slightly if a very long card code would overflow 92% of the card width
        val maxAllowedW = cardW * 0.92f
        val measuredW = paint.measureText(text)
        if (measuredW > maxAllowedW && measuredW > 1f) {
            paint.textSize = (targetTextSizePx * (maxAllowedW / measuredW)).coerceAtLeast(8f)
        }

        val halfW = paint.measureText(text) / 2f
        val fm = paint.fontMetrics
        val halfH = (fm.descent - fm.ascent) / 2f

        val minCx = (halfW + 3f).coerceAtMost(cardW / 2f)
        val maxCx = (cardW - halfW - 3f).coerceAtLeast(cardW / 2f)
        val cx = (relX * cardW).coerceIn(minCx, maxCx)

        val minCy = (halfH + 2f).coerceAtMost(cardH / 2f)
        val maxCy = (cardH - halfH - 2f).coerceAtLeast(cardH / 2f)
        val cy = (relY * cardH).coerceIn(minCy, maxCy)

        val baselineY = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, cx, baselineY, paint)
    }
}
