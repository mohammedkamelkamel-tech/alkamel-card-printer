package com.alkamel.cardprinter.data.printing

import android.graphics.RectF
import com.alkamel.cardprinter.data.local.entity.CardTemplateEntity
import kotlin.math.floor

/**
 * Computed aspect-ratio-locked A4 grid geometry shared identically by:
 * - CardDesignerScreen Full A4 Preview
 * - PrintPreviewScreen A4 Sheet Preview
 * - PdfPrintEngine A4 PDF Export & Direct Print
 */
data class A4SheetLayout(
    val pageWidth: Float,
    val pageHeight: Float,
    val margin: Float,
    val headerHeight: Float,
    val gap: Float,
    val columns: Int,
    val rows: Int,
    val cardWidthPx: Float,
    val cardHeightPx: Float,
    val gridStartX: Float,
    val gridStartY: Float
) {
    val cardsPerPage: Int get() = columns * rows

    fun cellRect(row: Int, colFromRight: Int): RectF {
        val physicalCol = (columns - 1) - colFromRight
        val left = gridStartX + physicalCol * (cardWidthPx + gap)
        val top = gridStartY + row * (cardHeightPx + gap)
        return RectF(left, top, left + cardWidthPx, top + cardHeightPx)
    }
}

/**
 * Single source of truth for card layout geometry, normalized (0.0..1.0) coordinates,
 * proportional text sizing, official ISO A4 dimensions, aspect-ratio-locked A4 layout,
 * and User Manager package-to-template matching.
 *
 * All element coordinates (relX, relY) are stored in physical LTR / top-to-bottom percentage space:
 * - relX = 0.0 -> physical left edge of card, 1.0 -> physical right edge of card
 * - relY = 0.0 -> physical top edge of card, 1.0 -> physical bottom edge of card
 */
object CardLayoutMetrics {

    // Official ISO A4 dimensions in PostScript points (210mm x 297mm @ 72pt/inch)
    // Ensures 1:1 physical printing on Android PrintManager & every PDF viewer with zero scaling drift
    const val A4_PDF_WIDTH = 595
    const val A4_PDF_HEIGHT = 842
    const val A4_PREVIEW_WIDTH = 952
    const val A4_PREVIEW_HEIGHT = 1347 // Exact 595:842 (210:297) ratio

    const val A4_MARGIN_RATIO = 16f / 595f
    const val A4_HEADER_RATIO = 24f / 842f
    const val A4_GAP_RATIO = 2.5f / 595f

    const val DEFAULT_USERNAME_X = 0.50f
    const val DEFAULT_USERNAME_Y = 0.52f
    const val DEFAULT_PASSWORD_X = 0.50f
    const val DEFAULT_PASSWORD_Y = 0.78f
    const val DEFAULT_QR_X = 0.84f
    const val DEFAULT_QR_Y = 0.50f

    const val MIN_REL_X = 0.06f
    const val MAX_REL_X = 0.94f
    const val MIN_REL_Y = 0.12f
    const val MAX_REL_Y = 0.88f

    const val MIN_QR_REL_X = 0.10f
    const val MAX_QR_REL_X = 0.90f
    const val MIN_QR_REL_Y = 0.14f
    const val MAX_QR_REL_Y = 0.86f

    // True ISO A4 printable reference space (ratio 832 : 1162 matches official A4 printable area)
    private const val A4_PRINTABLE_REF_W = 832f
    private const val A4_PRINTABLE_REF_H = 1162f
    private const val A4_CARD_GAP = 4f

    fun sanitizeRel(value: Float, fallback: Float, min: Float, max: Float): Float {
        if (!value.isFinite() || value <= 0f || value >= 1f) {
            return fallback.coerceIn(min, max)
        }
        return value.coerceIn(min, max)
    }

    fun sanitizeHexColor(hex: String?, fallback: String = "#000000"): String {
        val trimmed = hex?.trim().orEmpty()
        if (trimmed.isBlank()) return fallback
        return runCatching {
            android.graphics.Color.parseColor(trimmed)
            trimmed
        }.getOrDefault(fallback)
    }

    fun sanitize(template: CardTemplateEntity): CardTemplateEntity {
        val safeW = if (template.cardWidth.isFinite()) template.cardWidth.coerceIn(60f, 500f) else 203f
        val safeH = if (template.cardHeight.isFinite()) template.cardHeight.coerceIn(30f, 300f) else 61f
        val safeCols = template.columnsPerPage.coerceIn(1, 6)
        val safeRows = template.rowsPerPage.coerceIn(1, 30)
        val safeFont = if (template.fontSizeSp.isFinite()) template.fontSizeSp.coerceIn(10f, 28f) else 16f
        val safeShowUsername = if (!template.showUsername && !template.showPassword && !template.showQrCode) {
            true
        } else {
            template.showUsername
        }
        val defaultColor = if (!template.backgroundImagePath.isNullOrBlank()) "#000000" else "#FFFFFF"

        return template.copy(
            packageName = template.packageName.trim().ifBlank { "الكل" },
            cardWidth = safeW,
            cardHeight = safeH,
            columnsPerPage = safeCols,
            rowsPerPage = safeRows,
            showUsername = safeShowUsername,
            usernameX = sanitizeRel(template.usernameX, DEFAULT_USERNAME_X, MIN_REL_X, MAX_REL_X),
            usernameY = sanitizeRel(template.usernameY, DEFAULT_USERNAME_Y, MIN_REL_Y, MAX_REL_Y),
            passwordX = sanitizeRel(template.passwordX, DEFAULT_PASSWORD_X, MIN_REL_X, MAX_REL_X),
            passwordY = sanitizeRel(template.passwordY, DEFAULT_PASSWORD_Y, MIN_REL_Y, MAX_REL_Y),
            qrX = sanitizeRel(template.qrX, DEFAULT_QR_X, MIN_QR_REL_X, MAX_QR_REL_X),
            qrY = sanitizeRel(template.qrY, DEFAULT_QR_Y, MIN_QR_REL_Y, MAX_QR_REL_Y),
            textColorHex = sanitizeHexColor(template.textColorHex, defaultColor),
            fontSizeSp = safeFont
        )
    }

    fun computeCardAspectRatio(template: CardTemplateEntity): Float {
        val safeW = if (template.cardWidth.isFinite()) template.cardWidth.coerceIn(60f, 500f) else 203f
        val safeH = if (template.cardHeight.isFinite()) template.cardHeight.coerceIn(30f, 300f) else 61f
        return (safeW / safeH).coerceIn(0.8f, 4.8f)
    }

    /**
     * Computes exact, undistorted aspect-ratio-locked card placement on an ISO A4 page.
     * Guarantees every card cell has cardWidthPx / cardHeightPx == cardWidth / cardHeight
     * with zero horizontal stretching or vertical squishing.
     */
    fun computeA4SheetLayout(
        template: CardTemplateEntity,
        pageWidth: Float,
        pageHeight: Float,
        showPageHeader: Boolean
    ): A4SheetLayout {
        val safe = sanitize(template)
        val cols = safe.columnsPerPage.coerceIn(1, 6)
        val rows = safe.rowsPerPage.coerceIn(1, 30)
        val margin = pageWidth * A4_MARGIN_RATIO
        val headerH = if (showPageHeader) pageHeight * A4_HEADER_RATIO else 0f
        val gap = pageWidth * A4_GAP_RATIO

        val availW = (pageWidth - margin * 2f).coerceAtLeast(100f)
        val availH = (pageHeight - margin * 2f - headerH).coerceAtLeast(100f)

        val slotW = ((availW - gap * (cols - 1)) / cols).coerceAtLeast(16f)
        val slotH = ((availH - gap * (rows - 1)) / rows).coerceAtLeast(10f)
        val cardRatio = computeCardAspectRatio(safe)

        val fitW: Float
        val fitH: Float
        if (slotW / slotH > cardRatio) {
            fitH = slotH
            fitW = slotH * cardRatio
        } else {
            fitW = slotW
            fitH = slotW / cardRatio
        }

        val totalGridW = cols * fitW + (cols - 1) * gap
        val totalGridH = rows * fitH + (rows - 1) * gap
        val startX = margin + ((availW - totalGridW) / 2f).coerceAtLeast(0f)
        val startY = margin + headerH + ((availH - totalGridH) / 2f).coerceAtLeast(0f)

        return A4SheetLayout(
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            margin = margin,
            headerHeight = headerH,
            gap = gap,
            columns = cols,
            rows = rows,
            cardWidthPx = fitW,
            cardHeightPx = fitH,
            gridStartX = startX,
            gridStartY = startY
        )
    }

    /**
     * Exact proportional pixel text size shared by Interactive Preview, Full A4 Preview,
     * Print Preview, Direct Print, and Exported PDF.
     */
    fun computeUsernameTextPx(fontSizeSp: Float, cardWidthPx: Float, cardHeightPx: Float): Float {
        val factor = fontSizeSp.coerceIn(10f, 28f) / 16f
        val base = cardHeightPx * 0.29f * factor
        val minPx = (cardHeightPx * 0.13f).coerceAtLeast(4f)
        val maxPx = minOf(cardHeightPx * 0.62f, cardWidthPx * 0.22f).coerceAtLeast(minPx)
        return base.coerceIn(minPx, maxPx)
    }

    fun computePasswordTextPx(fontSizeSp: Float, cardWidthPx: Float, cardHeightPx: Float): Float {
        val uPx = computeUsernameTextPx(fontSizeSp, cardWidthPx, cardHeightPx)
        return (uPx * 0.84f).coerceAtLeast(3.5f)
    }

    fun computeQrSizePx(cardWidthPx: Float, cardHeightPx: Float): Float {
        return (minOf(cardWidthPx, cardHeightPx) * 0.44f).coerceIn(10f, minOf(cardWidthPx, cardHeightPx) * 0.72f)
    }

    fun calculateFittingColumns(cardWidth: Float): Int {
        val safeW = if (cardWidth.isFinite()) cardWidth.coerceIn(60f, 500f) else 203f
        return floor((A4_PRINTABLE_REF_W + A4_CARD_GAP) / (safeW + A4_CARD_GAP))
            .toInt()
            .coerceIn(1, 6)
    }

    fun calculateFittingRows(cardHeight: Float): Int {
        val safeH = if (cardHeight.isFinite()) cardHeight.coerceIn(30f, 300f) else 61f
        return floor((A4_PRINTABLE_REF_H + A4_CARD_GAP) / (safeH + A4_CARD_GAP))
            .toInt()
            .coerceIn(1, 30)
    }

    /**
     * Picks the best saved template for a User Manager package:
     * 1. Most recently updated template explicitly bound to that packageName
     * 2. Most recently updated general template bound to "الكل"
     * 3. Default template or most recently updated template
     */
    fun selectBestTemplateForPackage(
        templates: List<CardTemplateEntity>,
        packageName: String?
    ): CardTemplateEntity? {
        if (templates.isEmpty()) return null
        val target = packageName?.trim().orEmpty()
        val sortedByRecent = templates.sortedByDescending { it.updatedAt }

        if (target.isNotBlank() && target != "الكل") {
            val exactMatch = sortedByRecent.firstOrNull {
                it.packageName.trim().equals(target, ignoreCase = true)
            }
            if (exactMatch != null) return exactMatch
        }

        return sortedByRecent.firstOrNull { it.packageName.trim() == "الكل" }
            ?: sortedByRecent.firstOrNull { it.isDefault }
            ?: sortedByRecent.firstOrNull()
    }
}
