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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object CardBitmapRenderer {

    fun renderCardBitmap(
        card: CardEntity,
        template: CardTemplateEntity,
        widthPx: Int = 609,
        heightPx: Int = (widthPx * (template.cardHeight / template.cardWidth.coerceAtLeast(1f)))
            .roundToInt().coerceAtLeast(140)
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        var hasCustomBg = false
        template.backgroundImagePath?.takeIf { it.isNotBlank() }?.let { path ->
            val file = File(path)
            if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)?.let { decoded ->
                    val src = centerCropRect(decoded.width, decoded.height, widthPx, heightPx)
                    canvas.drawBitmap(decoded, src, Rect(0, 0, widthPx, heightPx), Paint(Paint.FILTER_BITMAP_FLAG))
                    decoded.recycle()
                    hasCustomBg = true
                }
            }
        }

        if (!hasCustomBg) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, widthPx.toFloat(), heightPx.toFloat(),
                    intArrayOf(Color.parseColor("#0F2342"), Color.parseColor("#1D4E89")),
                    null, Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat()), 10f, 10f, bgPaint)

            val barPaint = Paint().apply { color = Color.argb(40, 255, 255, 255) }
            canvas.drawRect(0f, 0f, widthPx.toFloat(), heightPx * 0.24f, barPaint)

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = (heightPx * 0.14f).coerceIn(13f, 24f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.RIGHT
            }
            canvas.drawText("الكامل نت • ${card.packageName}", widthPx - 14f, heightPx * 0.17f, titlePaint)
        }

        val textColor = runCatching { Color.parseColor(template.textColorHex) }
            .getOrDefault(if (hasCustomBg) Color.BLACK else Color.WHITE)
        val scale = widthPx / template.cardWidth.coerceAtLeast(100f)
        val fontSize = (template.fontSizeSp * scale * 0.85f).coerceIn(14f, heightPx * 0.34f)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = fontSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        if (template.showUsername) {
            canvas.drawText(
                card.username,
                template.usernameX.coerceIn(0.05f, 0.95f) * widthPx,
                template.usernameY.coerceIn(0.14f, 0.94f) * heightPx,
                paint
            )
        }
        if (template.showPassword && card.password.isNotBlank()) {
            val pPaint = Paint(paint).apply { textSize = (fontSize * 0.88f).coerceAtLeast(12f) }
            canvas.drawText(
                card.password,
                template.passwordX.coerceIn(0.05f, 0.95f) * widthPx,
                template.passwordY.coerceIn(0.14f, 0.94f) * heightPx,
                pPaint
            )
        }
        if (template.showPointOfSale && card.pointOfSale.isNotBlank()) {
            val posPaint = Paint(paint).apply {
                textSize = (fontSize * 0.72f).coerceAtLeast(11f)
                typeface = Typeface.DEFAULT
            }
            canvas.drawText(
                card.pointOfSale,
                template.pointOfSaleX.coerceIn(0.05f, 0.95f) * widthPx,
                template.pointOfSaleY.coerceIn(0.14f, 0.94f) * heightPx,
                posPaint
            )
        }
        if (template.showQrCode) {
            val qrSize = (min(widthPx, heightPx) * 0.48f).roundToInt().coerceIn(54, 176)
            val data = if (card.password.isNotBlank()) "U:${card.username}|P:${card.password}" else card.username
            QrCodeBitmapGenerator.generateQrBitmap(data, qrSize)?.let { qr ->
                val left = (template.qrX.coerceIn(0.1f, 0.9f) * widthPx - qrSize / 2f)
                    .coerceIn(4f, (widthPx - qrSize - 4).toFloat())
                val top = (template.qrY.coerceIn(0.15f, 0.85f) * heightPx - qrSize / 2f)
                    .coerceIn(4f, (heightPx - qrSize - 4).toFloat())
                canvas.drawRect(left - 2f, top - 2f, left + qrSize + 2f, top + qrSize + 2f, Paint().apply { color = Color.WHITE })
                canvas.drawBitmap(qr, left, top, null)
            }
        }
        return bitmap
    }

    private fun centerCropRect(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Rect {
        val sr = srcW.toFloat() / max(1, srcH)
        val dr = dstW.toFloat() / max(1, dstH)
        return if (sr > dr) {
            val w = (srcH * dr).roundToInt()
            Rect((srcW - w) / 2, 0, (srcW + w) / 2, srcH)
        } else {
            val h = (srcW / dr).roundToInt()
            Rect(0, (srcH - h) / 2, srcW, (srcH + h) / 2)
        }
    }
}
