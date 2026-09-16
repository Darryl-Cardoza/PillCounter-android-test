package com.rite.pillcounting.feature.history.presentation.compose

import android.content.Context
import android.graphics.Canvas
import com.rite.pillcounting.core.utils.logger.AppLogger
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextUtils
import com.rite.pillcounting.R
import com.rite.pillcounting.feature.history.domain.model.TxnWithDrugDto
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryPdfExporter(private val context: Context) {

    private val logger = AppLogger("HistoryPdfExporter")

    // A4 Dimensions (Points)
    private val pageWidth = 595
    private val pageHeight = 842
    private val margin = 40
    private val contentWidth = pageWidth - (2 * margin)

    // Row settings
    private val rowHeight = 30f
    private val headerHeight = 35f

    // --- Paints ---
    private val titlePaint = Paint().apply {
        color = Color.BLACK
        textSize = 20f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val subTitlePaint = Paint().apply {
        color = Color.BLACK
        textSize = 14f
        typeface = Typeface.DEFAULT
        textAlign = Paint.Align.CENTER
    }

    private val tableHeaderBgPaint = Paint().apply {
        color = Color.DKGRAY
        style = Paint.Style.FILL
    }

    private val tableHeaderTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 10f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val cellTextPaint = Paint().apply {
        color = Color.BLACK
        textSize = 10f
        typeface = Typeface.DEFAULT
        textAlign = Paint.Align.CENTER
    }

    private val borderPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    // UPDATED: Now styled as normal body text (Black, larger size)
    private val timestampPaint = Paint().apply {
        color = Color.BLACK
        textSize = 12f // Matched closer to body
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    fun generateHistoryPdf(counts: List<TxnWithDrugDto>, selectedDate: String): File? {
        // 1. Setup Path
        val folderName = "PillReports/DailyHistoryReports"
        val directory = File(context.getExternalFilesDir(null), folderName)

        if (!directory.exists() && !directory.mkdirs()) return null

        // 2. Define strict filename
        val fileName = "DrugHistory_$selectedDate.pdf"
        val file = File(directory, fileName)

        // 3. STRICT CLEANUP
        if (file.exists()) {
            val deleted = file.delete()
            if (!deleted) {
                // If delete fails, it usually means the file is OPEN in another app.
                // We will try to overwrite it anyway using the stream below,
                // but sometimes the OS locks it.
                logger.e("Could not delete existing file. It might be open.")
            }
        }

        // 4. Create New PDF Document
        val pdfDocument = PdfDocument()

        try {
            // Column Widths
            val col1 = contentWidth * 0.38f
            val col2 = contentWidth * 0.25f
            val col3 = contentWidth * 0.17f
            val col4 = contentWidth * 0.20f

            val colWidths = floatArrayOf(col1, col2, col3, col4)
            val headers = arrayOf(
                context.getString(R.string.drugname_two_lines),
                context.getString(R.string.ndc_gtin14),
                context.getString(R.string.pills_count),
                context.getString(R.string.status)
            )

            // Page 1 Setup
            var pageNumber = 1
            var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            var page = pdfDocument.startPage(pageInfo)
            var canvas = page.canvas
            var yPosition = margin.toFloat()

            // Draw Titles
            yPosition += 30
            canvas.drawText("Drug History Report", (pageWidth / 2).toFloat(), yPosition, titlePaint)
            yPosition += 25
            canvas.drawText(
                "Selected Date: $selectedDate",
                (pageWidth / 2).toFloat(),
                yPosition,
                subTitlePaint
            )
            yPosition += 30

            if (counts.isEmpty()) {
                yPosition += 50
                canvas.drawText(
                    "No data available for the selected date.",
                    (pageWidth / 2).toFloat(),
                    yPosition,
                    subTitlePaint
                )
            } else {
                drawTableHeader(canvas, margin.toFloat(), yPosition, colWidths, headers)
                yPosition += headerHeight

                for (item in counts) {
                    // Check for Page Break
                    if (yPosition + rowHeight > pageHeight - margin - 30) {
                        pdfDocument.finishPage(page)
                        pageNumber++
                        pageInfo =
                            PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                        page = pdfDocument.startPage(pageInfo)
                        canvas = page.canvas
                        yPosition = margin.toFloat() + 30
                        drawTableHeader(canvas, margin.toFloat(), yPosition, colWidths, headers)
                        yPosition += headerHeight
                    }

                    val rowData = arrayOf(
                        item.drugName ?: "",
                        item.ndc ?: "N/A",
                        item.pillCount?.toString() ?: "0",
                        item.status.toString()
                    )

                    drawTableRow(canvas, margin.toFloat(), yPosition, colWidths, rowData)
                    yPosition += rowHeight
                }
            }

            // UPDATED: Draw Timestamp flowing after the table (not pinned to bottom)
            yPosition += 30

            // Check if we have space for the timestamp, if not, new page
            if (yPosition + 20 > pageHeight - margin) {
                pdfDocument.finishPage(page)
                pageNumber++
                pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                page = pdfDocument.startPage(pageInfo)
                canvas = page.canvas
                yPosition = margin.toFloat() + 30
            }

            val footerText = "Generated on: ${
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            }"

            canvas.drawText(
                footerText,
                (pageWidth / 2).toFloat(),
                yPosition,
                timestampPaint
            )

            pdfDocument.finishPage(page)

            // Write File
            val fos = FileOutputStream(file)
            pdfDocument.writeTo(fos)
            fos.close()

            return file

        } catch (e: Exception) {
            e.printStackTrace()
            try {
                pdfDocument.close()
            } catch (ex: Exception) { /* ignore */
            }
            return null
        } finally {
            pdfDocument.close()
        }
    }

    private fun drawTableHeader(
        canvas: Canvas,
        startX: Float,
        y: Float,
        colWidths: FloatArray,
        headers: Array<String>
    ) {
        var currentX = startX
        val totalWidth = colWidths.sum()
        canvas.drawRect(startX, y, startX + totalWidth, y + headerHeight, tableHeaderBgPaint)

        for (i in headers.indices) {
            val width = colWidths[i]
            canvas.drawRect(currentX, y, currentX + width, y + headerHeight, borderPaint)

            val textX = currentX + (width / 2)
            val textY =
                y + (headerHeight / 2) - ((tableHeaderTextPaint.descent() + tableHeaderTextPaint.ascent()) / 2)

            val safeHeader = headers[i].replace("\n", " ")
            canvas.drawText(safeHeader, textX, textY, tableHeaderTextPaint)
            currentX += width
        }
    }

    private fun drawTableRow(
        canvas: Canvas,
        startX: Float,
        y: Float,
        colWidths: FloatArray,
        data: Array<String>
    ) {
        var currentX = startX
        for (i in data.indices) {
            val width = colWidths[i]
            canvas.drawRect(currentX, y, currentX + width, y + rowHeight, borderPaint)

            val textX = currentX + (width / 2)
            val textY =
                y + (rowHeight / 2) - ((cellTextPaint.descent() + cellTextPaint.ascent()) / 2)

            val truncatedText = TextUtils.ellipsize(
                data[i],
                android.text.TextPaint(cellTextPaint),
                width - 10f,
                TextUtils.TruncateAt.END
            ).toString()

            canvas.drawText(truncatedText, textX, textY, cellTextPaint)
            currentX += width
        }
    }
}
