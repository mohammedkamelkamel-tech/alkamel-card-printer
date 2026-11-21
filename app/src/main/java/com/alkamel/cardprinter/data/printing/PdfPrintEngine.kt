package com.alkamel.cardprinter.data.printing

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.core.content.FileProvider
import com.alkamel.cardprinter.data.local.entity.CardEntity
import com.alkamel.cardprinter.data.local.entity.CardTemplateEntity
import com.alkamel.cardprinter.data.local.entity.PrintSalesLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

sealed class PdfResult {
    data class Success(val file: File, val uri: Uri) : PdfResult()
    data class Error(val message: String) : PdfResult()
}

object PdfPrintEngine {

    private const val A4_W = 1240
    private const val A4_H = 1754
    private const val MARGIN = 30f
    private const val HEADER_H = 46f

    suspend fun generateCardsGridPdf(
        context: Context,
        cards: List<CardEntity>,
        template: CardTemplateEntity,
        title: String = "الكامل-طباعة الكروت",
        showCutGuides: Boolean = true,
        showPageHeader: Boolean = true
    ): PdfResult = withContext(Dispatchers.IO) {
        if (cards.isEmpty()) return@withContext PdfResult.Error("لا توجد كروت لتوليد ملف PDF A4.")
        val doc = PdfDocument()
        try {
            val cols = template.columnsPerPage.coerceIn(1, 6)
            val rows = template.rowsPerPage.coerceIn(1, 30)
            val perPage = cols * rows
            val pages = ceil(cards.size.toDouble() / perPage).toInt().coerceAtLeast(1)

            val topHeaderSpace = if (showPageHeader) HEADER_H else 0f
            val gap = 4f
            val cellW = (A4_W - MARGIN * 2 - gap * (cols - 1)) / cols
            val ratio = template.cardHeight.coerceAtLeast(20f) / template.cardWidth.coerceAtLeast(40f)
            val maxRowH = (A4_H - MARGIN * 2 - topHeaderSpace - gap * (rows - 1)) / rows
            val cellH = minOf(cellW * ratio, maxRowH)

            val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date())
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0F172A")
                textSize = 20f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.RIGHT
            }
            val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#475569")
                textSize = 17f
                textAlign = Paint.Align.LEFT
            }
            val cutPaint = Paint().apply {
                color = Color.parseColor("#94A3B8")
                style = Paint.Style.STROKE
                strokeWidth = 1f
            }

            for (p in 0 until pages) {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(A4_W, A4_H, p + 1).create())
                val c = page.canvas
                c.drawColor(Color.WHITE)

                if (showPageHeader) {
                    c.drawText("$title • ${template.name} (${cards.size} كرت)", A4_W - MARGIN, MARGIN + 22f, titlePaint)
                    c.drawText("A4 • صفحة ${p + 1}/$pages  |  $dateStr", MARGIN, MARGIN + 22f, metaPaint)
                }

                val slice = cards.subList(p * perPage, minOf((p + 1) * perPage, cards.size))
                slice.forEachIndexed { idx, card ->
                    val r = idx / cols
                    val col = (cols - 1) - (idx % cols)
                    val left = MARGIN + col * (cellW + gap)
                    val top = MARGIN + topHeaderSpace + r * (cellH + gap)
                    val dest = RectF(left, top, left + cellW, top + cellH)
                    val bmp = CardBitmapRenderer.renderCardBitmap(
                        card = card,
                        template = template,
                        widthPx = cellW.roundToInt().coerceAtLeast(220),
                        heightPx = cellH.roundToInt().coerceAtLeast(64)
                    )
                    c.drawBitmap(bmp, null, dest, Paint(Paint.FILTER_BITMAP_FLAG))
                    if (showCutGuides) c.drawRect(dest, cutPaint)
                    bmp.recycle()
                }
                doc.finishPage(page)
            }
            saveDocument(context, doc, "alkamel_a4_cards")
        } catch (e: Exception) {
            PdfResult.Error("تعذر توليد ملف PDF A4: ${e.localizedMessage ?: "خطأ غير متوقع"}")
        } finally {
            doc.close()
        }
    }

    suspend fun generateSalesReportPdf(
        context: Context,
        fromDateKey: String,
        toDateKey: String,
        logs: List<PrintSalesLogEntity>,
        totalAmount: Double,
        totalCards: Int,
        topPackage: String
    ): PdfResult = withContext(Dispatchers.IO) {
        val doc = PdfDocument()
        try {
            val rowsPerPage = 24
            val pages = ceil(maxOf(1, logs.size).toDouble() / rowsPerPage).toInt()
            val bannerPaint = Paint().apply { color = Color.parseColor("#111B2E") }
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 30f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); textAlign = Paint.Align.RIGHT
            }
            val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#CBD5E1"); textSize = 19f; textAlign = Paint.Align.RIGHT
            }
            val headBg = Paint().apply { color = Color.parseColor("#1D4E89") }
            val headTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 19f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); textAlign = Paint.Align.RIGHT
            }
            val cellTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0F172A"); textSize = 18f; textAlign = Paint.Align.RIGHT
            }
            val linePaint = Paint().apply { color = Color.parseColor("#CBD5E1"); strokeWidth = 1.2f }
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

            for (p in 0 until pages) {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(A4_W, A4_H, p + 1).create())
                val c = page.canvas
                c.drawColor(Color.WHITE)
                c.drawRect(0f, 0f, A4_W.toFloat(), 145f, bannerPaint)
                c.drawText("الكامل-طباعة الكروت | تقرير المبيعات (A4)", A4_W - 44f, 64f, titlePaint)
                c.drawText("الفترة: $fromDateKey إلى $toDateKey  •  صفحة ${p + 1}/$pages", A4_W - 44f, 108f, subPaint)

                var y = 170f
                if (p == 0) {
                    c.drawRoundRect(RectF(40f, y, A4_W - 40f, y + 96f), 14f, 14f, Paint().apply { color = Color.parseColor("#F1F5F9") })
                    val boldCell = Paint(cellTxt).apply { typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); textSize = 21f }
                    c.drawText("إجمالي المبيعات: ${totalAmount.toLong()} ريال", A4_W - 64f, y + 40f, boldCell)
                    c.drawText("عدد الكروت: $totalCards كرت  |  الأكثر مبيعاً: $topPackage", A4_W - 64f, y + 78f, cellTxt)
                    y += 120f
                }

                c.drawRect(40f, y, A4_W - 40f, y + 46f, headBg)
                c.drawText("رقم الكرت / العملية", A4_W - 54f, y + 30f, headTxt)
                c.drawText("الباقة", A4_W - 350f, y + 30f, headTxt)
                c.drawText("السعر", A4_W - 520f, y + 30f, headTxt)
                c.drawText("الحساب", A4_W - 680f, y + 30f, headTxt)
                c.drawText("التحميل", A4_W - 840f, y + 30f, headTxt)
                c.drawText("التاريخ", A4_W - 1010f, y + 30f, headTxt)
                y += 48f

                val slice = if (logs.isEmpty()) emptyList() else logs.subList(p * rowsPerPage, minOf((p + 1) * rowsPerPage, logs.size))
                if (slice.isEmpty()) {
                    c.drawText("لا توجد عمليات مبيعات خلال الفترة المحددة.", A4_W - 64f, y + 42f, cellTxt)
                } else {
                    slice.forEach { log ->
                        val rowY = y + 30f
                        c.drawText(log.cardUsername.take(18), A4_W - 54f, rowY, cellTxt)
                        c.drawText(log.packageName.take(12), A4_W - 350f, rowY, cellTxt)
                        c.drawText("${log.totalAmount.toLong()}", A4_W - 520f, rowY, cellTxt)
                        c.drawText(log.serverAccount.ifBlank { "-" }.take(10), A4_W - 680f, rowY, cellTxt)
                        c.drawText(log.downloadLabel.ifBlank { "0 MB" }.take(10), A4_W - 840f, rowY, cellTxt)
                        c.drawText(df.format(Date(log.createdAt)), A4_W - 1010f, rowY, cellTxt)
                        y += 44f
                        c.drawLine(40f, y, A4_W - 40f, y, linePaint)
                    }
                }
                doc.finishPage(page)
            }
            saveDocument(context, doc, "alkamel_a4_sales_report")
        } catch (e: Exception) {
            PdfResult.Error("تعذر توليد تقرير PDF: ${e.localizedMessage ?: ""}")
        } finally {
            doc.close()
        }
    }

    fun printPdfWithAndroidPrintManager(
        context: Context,
        pdfFile: File,
        jobName: String = "الكامل-طباعة الكروت A4"
    ): Boolean {
        val activity = context.findActivity() ?: return false
        val printManager = activity.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return false
        val attributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
        printManager.print(jobName, PdfFilePrintDocumentAdapter(pdfFile, jobName), attributes)
        return true
    }

    fun createSharePdfIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "مشاركة ملف PDF (A4)"
        )
    }

    fun createOpenPdfIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent.createChooser(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "فتح ملف PDF (A4)"
        )
    }

    private fun saveDocument(context: Context, doc: PdfDocument, prefix: String): PdfResult {
        val dir = File(context.cacheDir, "pdfs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "${prefix}_$stamp.pdf")
        FileOutputStream(file).use { doc.writeTo(it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return PdfResult.Success(file, uri)
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    private class PdfFilePrintDocumentAdapter(
        private val file: File,
        private val documentName: String
    ) : PrintDocumentAdapter() {

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback?,
            extras: Bundle?
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback?.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(documentName)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build()
            callback?.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor?,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback?
        ) {
            if (destination == null || !file.exists()) {
                callback?.onWriteFailed("تعذر قراءة ملف PDF للطباعة.")
                return
            }
            try {
                FileInputStream(file).use { input ->
                    FileOutputStream(destination.fileDescriptor).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } > 0) {
                            if (cancellationSignal?.isCanceled == true) {
                                callback?.onWriteCancelled()
                                return
                            }
                            output.write(buffer, 0, bytesRead)
                        }
                        output.flush()
                    }
                }
                callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback?.onWriteFailed(e.localizedMessage ?: "فشلت الطباعة")
            }
        }
    }
}
