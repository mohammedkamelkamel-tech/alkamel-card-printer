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
import com.alkamel.cardprinter.ui.components.DesignerElement
import com.alkamel.cardprinter.ui.components.QrCodeBitmapGenerator
import java.io.File
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Single unified rendering engine shared identically across the entire app:
 * 1. Interactive Single-Card Designer Preview (CardDesignPreviewBox)
 * 2. Designer Full A4 Page Preview Dialog
 * 3. PrintPreviewScreen Live ISO A4 Preview
 * 4. Exported A4 PDF (PdfPrintEngine)
 * 5. Direct Android PrintManager Printing
 */
object CardBitmapRenderer {

    private data class ComputedTextPlacement(
        val paint: Paint,
        val cx: Float,
        val cy: Float,
        val baselineY: Float,
        val bounds: RectF
    )

    /**
     * Decodes a template's independent background image once so rendering a full A4 page
     * reuses the same decoded Bitmap without repeated disk I/O.
     */
    fun decodeBackgroundBitmap(template: CardTemplateEntity): Bitmap? {
        val path = template.backgroundImagePath?.takeIf { it.isNotBlank() } ?: return null
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    /**
     * Computes the exact rendered bounding box of [element] inside a card of size [cardW] x [cardH]
     * using the identical font/QR layout math used by [drawCardOnCanvas].
     */
    fun computeElementBounds(
        element: DesignerElement,
        sampleUsername: String,
        samplePassword: String,
        template: CardTemplateEntity,
        cardW: Float,
        cardH: Float
    ): RectF? {
        val safe = CardLayoutMetrics.sanitize(template)
        val safeW = cardW.coerceAtLeast(24f)
        val safeH = cardH.coerceAtLeast(12f)
        return when (element) {
            DesignerElement.USERNAME -> {
                if (!safe.showUsername || sampleUsername.isBlank()) null
                else {
                    val textPx = CardLayoutMetrics.computeUsernameTextPx(safe.fontSizeSp, safeW, safeH)
                    computeTextPlacement(
                        text = sampleUsername,
                        relX = safe.usernameX,
                        relY = safe.usernameY,
                        cardW = safeW,
                        cardH = safeH,
                        color = Color.BLACK,
                        targetTextSizePx = textPx
                    ).bounds
                }
            }
            DesignerElement.PASSWORD -> {
                if (!safe.showPassword || samplePassword.isBlank()) null
                else {
                    val textPx = CardLayoutMetrics.computePasswordTextPx(safe.fontSizeSp, safeW, safeH)
                    computeTextPlacement(
                        text = samplePassword,
                        relX = safe.passwordX,
                        relY = safe.passwordY,
                        cardW = safeW,
                        cardH = safeH,
                        color = Color.BLACK,
                        targetTextSizePx = textPx
                    ).bounds
                }
            }
            DesignerElement.QR_CODE -> {
                if (!safe.showQrCode) null
                else computeQrBounds(safe, safeW, safeH)
            }
        }
    }

    /**
     * Hit-tests a touch point ([touchX], [touchY]) inside a card of size [cardW] x [cardH]
     * against all visible elements, returning the matching element with generous touch padding.
     */
    fun hitTestElement(
        touchX: Float,
        touchY: Float,
        sampleUsername: String,
        samplePassword: String,
        template: CardTemplateEntity,
        cardW: Float,
        cardH: Float
    ): DesignerElement? {
        val pad = minOf(cardW, cardH) * 0.14f
        val candidates = listOf(DesignerElement.USERNAME, DesignerElement.PASSWORD, DesignerElement.QR_CODE)
        var bestElement: DesignerElement? = null
        var bestDist = Float.MAX_VALUE
        for (el in candidates) {
            val rect = computeElementBounds(el, sampleUsername, samplePassword, template, cardW, cardH) ?: continue
            val expanded = RectF(rect.left - pad, rect.top - pad, rect.right + pad, rect.bottom + pad)
            if (expanded.contains(touchX, touchY)) {
                val dist = hypot(touchX - rect.centerX(), touchY - rect.centerY())
                if (dist < bestDist) {
                    bestDist = dist
                    bestElement = el
                }
            }
        }
        return bestElement
    }

    /**
     * Draws a complete ISO A4 page directly onto [canvas] (PDF page or Bitmap preview)
     * with aspect-ratio-locked card cells so exported PDFs, direct printouts, and previews match 100%.
     */
    fun drawA4PageOnCanvas(
        canvas: Canvas,
        pageWidth: Float,
        pageHeight: Float,
        cardsOnPage: List<CardEntity>,
        template: CardTemplateEntity,
        headerTitle: String,
        headerMeta: String,
        showCutGuides: Boolean,
        showPageHeader: Boolean,
        preloadedBackground: Bitmap?
    ) {
        val safeTemplate = CardLayoutMetrics.sanitize(template)
        val layout = CardLayoutMetrics.computeA4SheetLayout(
            template = safeTemplate,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            showPageHeader = showPageHeader
        )

        canvas.drawColor(Color.WHITE)

        if (showPageHeader) {
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0F172A")
                textSize = (pageWidth * 0.017f).coerceAtLeast(8f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.RIGHT
            }
            val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#475569")
                textSize = (pageWidth * 0.014f).coerceAtLeast(7f)
                textAlign = Paint.Align.LEFT
            }
            val headerBaseline = layout.margin + layout.headerHeight * 0.56f
            canvas.drawText(headerTitle, pageWidth - layout.margin, headerBaseline, titlePaint)
            canvas.drawText(headerMeta, layout.margin, headerBaseline, metaPaint)
        }

        val cutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8")
            style = Paint.Style.STROKE
            strokeWidth = (pageWidth / 1000f).coerceIn(0.45f, 1.2f)
        }

        val maxCards = layout.cardsPerPage
        val count = minOf(cardsOnPage.size, maxCards)
        for (idx in 0 until count) {
            val row = idx / layout.columns
            val colFromRight = idx % layout.columns
            val dest = layout.cellRect(row = row, colFromRight = colFromRight)
            drawCardOnCanvas(
                canvas = canvas,
                dest = dest,
                card = cardsOnPage[idx],
                template = safeTemplate,
                preloadedBackground = preloadedBackground
            )
            if (showCutGuides) {
                canvas.drawRect(dest, cutPaint)
            }
        }
    }

    /**
     * Renders a crisp ISO A4 preview Bitmap using the exact same [drawA4PageOnCanvas] engine
     * used by [PdfPrintEngine].
     */
    fun renderA4PageBitmap(
        cardsOnPage: List<CardEntity>,
        template: CardTemplateEntity,
        headerTitle: String,
        headerMeta: String,
        showCutGuides: Boolean = true,
        showPageHeader: Boolean = true,
        pageWidthPx: Int = CardLayoutMetrics.A4_PREVIEW_WIDTH,
        pageHeightPx: Int = CardLayoutMetrics.A4_PREVIEW_HEIGHT
    ): Bitmap {
        val safeTemplate = CardLayoutMetrics.sanitize(template)
        val pageBmp = Bitmap.createBitmap(pageWidthPx, pageHeightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(pageBmp)
        val preloadedBg = decodeBackgroundBitmap(safeTemplate)
        try {
            drawA4PageOnCanvas(
                canvas = canvas,
                pageWidth = pageWidthPx.toFloat(),
                pageHeight = pageHeightPx.toFloat(),
                cardsOnPage = cardsOnPage,
                template = safeTemplate,
                headerTitle = headerTitle,
                headerMeta = headerMeta,
                showCutGuides = showCutGuides,
                showPageHeader = showPageHeader,
                preloadedBackground = preloadedBg
            )
        } finally {
            preloadedBg?.recycle()
        }
        return pageBmp
    }

    /**
     * Draws a single card directly into [dest] on [canvas] with crisp vector text,
     * exact percentage-locked (0.0..1.0) coordinates, and optional interactive selection frame.
     */
    fun drawCardOnCanvas(
        canvas: Canvas,
        dest: RectF,
        card: CardEntity,
        template: CardTemplateEntity,
        preloadedBackground: Bitmap? = null,
        selectedElementHighlight: DesignerElement? = null
    ) {
        val safeTemplate = CardLayoutMetrics.sanitize(template)
        val cardW = dest.width().coerceAtLeast(24f)
        val cardH = dest.height().coerceAtLeast(12f)

        val saveCount = canvas.save()
        canvas.clipRect(dest)
        canvas.translate(dest.left, dest.top)

        var hasCustomBg = false
        val ownDecoded = if (preloadedBackground != null && !preloadedBackground.isRecycled) {
            null
        } else {
            decodeBackgroundBitmap(safeTemplate)
        }
        val activeBg = preloadedBackground?.takeIf { !it.isRecycled } ?: ownDecoded

        if (activeBg != null) {
            val src = Rect(0, 0, activeBg.width, activeBg.height)
            val dstRect = RectF(0f, 0f, cardW, cardH)
            canvas.drawBitmap(activeBg, src, dstRect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
            hasCustomBg = true
        }
        ownDecoded?.recycle()

        if (!hasCustomBg) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, cardW, cardH,
                    intArrayOf(Color.parseColor("#0F2342"), Color.parseColor("#1D4E89")),
                    null, Shader.TileMode.CLAMP
                )
            }
            val radius = (minOf(cardW, cardH) * 0.06f).coerceIn(1.5f, 8f)
            canvas.drawRoundRect(RectF(0f, 0f, cardW, cardH), radius, radius, bgPaint)

            val barPaint = Paint().apply { color = Color.argb(40, 255, 255, 255) }
            canvas.drawRect(0f, 0f, cardW, cardH * 0.24f, barPaint)

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = (cardH * 0.14f).coerceIn(4.5f, 20f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.RIGHT
            }
            val headerLabel = if (card.packageName.isNotBlank()) "الكامل نت • ${card.packageName}" else "الكامل نت"
            canvas.drawText(headerLabel, cardW - (cardW * 0.03f), cardH * 0.17f, titlePaint)
        }

        val textColor = runCatching { Color.parseColor(safeTemplate.textColorHex) }
            .getOrDefault(if (hasCustomBg) Color.BLACK else Color.WHITE)

        val usernameFontPx = CardLayoutMetrics.computeUsernameTextPx(
            fontSizeSp = safeTemplate.fontSizeSp,
            cardWidthPx = cardW,
            cardHeightPx = cardH
        )
        val passwordFontPx = CardLayoutMetrics.computePasswordTextPx(
            fontSizeSp = safeTemplate.fontSizeSp,
            cardWidthPx = cardW,
            cardHeightPx = cardH
        )

        if (safeTemplate.showUsername && card.username.isNotBlank()) {
            val placed = computeTextPlacement(
                text = card.username,
                relX = safeTemplate.usernameX,
                relY = safeTemplate.usernameY,
                cardW = cardW,
                cardH = cardH,
                color = textColor,
                targetTextSizePx = usernameFontPx
            )
            if (selectedElementHighlight == DesignerElement.USERNAME) {
                drawInteractiveHighlight(canvas, placed.bounds, cardW, cardH)
            }
            canvas.drawText(card.username, placed.cx, placed.baselineY, placed.paint)
        }

        if (safeTemplate.showPassword && card.password.isNotBlank()) {
            val placed = computeTextPlacement(
                text = card.password,
                relX = safeTemplate.passwordX,
                relY = safeTemplate.passwordY,
                cardW = cardW,
                cardH = cardH,
                color = textColor,
                targetTextSizePx = passwordFontPx
            )
            if (selectedElementHighlight == DesignerElement.PASSWORD) {
                drawInteractiveHighlight(canvas, placed.bounds, cardW, cardH)
            }
            canvas.drawText(card.password, placed.cx, placed.baselineY, placed.paint)
        }

        if (safeTemplate.showQrCode) {
            val qrRect = computeQrBounds(safeTemplate, cardW, cardH)
            val qrRenderPx = (qrRect.width() * 2f).roundToInt().coerceIn(48, 220)
            val data = if (card.password.isNotBlank()) "U:${card.username}|P:${card.password}" else card.username
            QrCodeBitmapGenerator.generateQrBitmap(data, qrRenderPx)?.let { qr ->
                val pad = (qrRect.width() * 0.05f).coerceIn(0.8f, 3f)
                val bgRect = RectF(
                    (qrRect.left - pad).coerceAtLeast(0f),
                    (qrRect.top - pad).coerceAtLeast(0f),
                    (qrRect.right + pad).coerceAtMost(cardW),
                    (qrRect.bottom + pad).coerceAtMost(cardH)
                )
                canvas.drawRect(bgRect, Paint().apply { color = Color.WHITE })
                canvas.drawBitmap(qr, null, qrRect, Paint(Paint.FILTER_BITMAP_FLAG))
                if (selectedElementHighlight == DesignerElement.QR_CODE) {
                    drawInteractiveHighlight(canvas, bgRect, cardW, cardH)
                }
                qr.recycle()
            }
        }

        canvas.restoreToCount(saveCount)
    }

    fun renderCardBitmap(
        card: CardEntity,
        template: CardTemplateEntity,
        widthPx: Int = 609,
        heightPx: Int = (widthPx * (template.cardHeight / template.cardWidth.coerceAtLeast(1f)))
            .roundToInt().coerceAtLeast(140),
        preloadedBackground: Bitmap? = null
    ): Bitmap {
        val safeW = widthPx.coerceAtLeast(60)
        val safeH = heightPx.coerceAtLeast(24)
        val bitmap = Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawCardOnCanvas(
            canvas = canvas,
            dest = RectF(0f, 0f, safeW.toFloat(), safeH.toFloat()),
            card = card,
            template = template,
            preloadedBackground = preloadedBackground
        )
        return bitmap
    }

    private fun computeQrBounds(safeTemplate: CardTemplateEntity, cardW: Float, cardH: Float): RectF {
        val qrSizeF = CardLayoutMetrics.computeQrSizePx(cardW, cardH)
        val maxLeft = (cardW - qrSizeF).coerceAtLeast(0f)
        val maxTop = (cardH - qrSizeF).coerceAtLeast(0f)
        val left = (safeTemplate.qrX * cardW - qrSizeF / 2f).coerceIn(0f, maxLeft)
        val top = (safeTemplate.qrY * cardH - qrSizeF / 2f).coerceIn(0f, maxTop)
        return RectF(left, top, left + qrSizeF, top + qrSizeF)
    }

    private fun computeTextPlacement(
        text: String,
        relX: Float,
        relY: Float,
        cardW: Float,
        cardH: Float,
        color: Int,
        targetTextSizePx: Float
    ): ComputedTextPlacement {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = targetTextSizePx
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val maxAllowedW = cardW * 0.92f
        val measuredW = paint.measureText(text)
        if (measuredW > maxAllowedW && measuredW > 1f) {
            paint.textSize = (targetTextSizePx * (maxAllowedW / measuredW)).coerceAtLeast(4f)
        }

        val halfW = paint.measureText(text) / 2f
        val fm = paint.fontMetrics
        val halfH = (fm.descent - fm.ascent) / 2f

        val padX = (cardW * 0.02f).coerceIn(1f, 4f)
        val padY = (cardH * 0.03f).coerceIn(0.8f, 3f)

        val minCx = (halfW + padX).coerceAtMost(cardW / 2f)
        val maxCx = (cardW - halfW - padX).coerceAtLeast(cardW / 2f)
        val cx = (relX * cardW).coerceIn(minCx, maxCx)

        val minCy = (halfH + padY).coerceAtMost(cardH / 2f)
        val maxCy = (cardH - halfH - padY).coerceAtLeast(cardH / 2f)
        val cy = (relY * cardH).coerceIn(minCy, maxCy)

        val baselineY = cy - (fm.ascent + fm.descent) / 2f
        val bounds = RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        return ComputedTextPlacement(paint, cx, cy, baselineY, bounds)
    }

    private fun drawInteractiveHighlight(canvas: Canvas, bounds: RectF, cardW: Float, cardH: Float) {
        val pad = (minOf(cardW, cardH) * 0.03f).coerceIn(3f, 8f)
        val rect = RectF(
            (bounds.left - pad).coerceAtLeast(1f),
            (bounds.top - pad * 0.6f).coerceAtLeast(1f),
            (bounds.right + pad).coerceAtMost(cardW - 1f),
            (bounds.bottom + pad * 0.6f).coerceAtMost(cardH - 1f)
        )
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(38, 0, 0, 0)
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFCA28")
            style = Paint.Style.STROKE
            strokeWidth = (minOf(cardW, cardH) * 0.015f).coerceIn(2f, 4f)
        }
        canvas.drawRoundRect(rect, 6f, 6f, fillPaint)
        canvas.drawRoundRect(rect, 6f, 6f, strokePaint)
    }
}
