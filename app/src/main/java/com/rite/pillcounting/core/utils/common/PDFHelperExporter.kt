package com.rite.pillcounting.core.utils.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.logger.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PDFHelperExporter(private val context: Context) {

    private val logger = AppLogger("PDFHelperExporter")

    // A4 Dimensions
    private val pageWidth = 595
    private val pageHeight = 842
    private val margin = 50

    // --- Paint Objects ---
    private val titlePaint = Paint().apply {
        color = Color.BLACK
        textSize = 24f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val headerPaint = Paint().apply {
        color = Color.BLACK
        textSize = 18f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val cellTextPaint = Paint().apply {
        color = Color.BLACK
        textSize = 14f
        typeface = Typeface.DEFAULT
    }

    private val cellLabelPaint = Paint().apply {
        color = Color.BLACK
        textSize = 14f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val tableHeaderBgPaint = Paint().apply {
        color = Color.DKGRAY
        style = Paint.Style.FILL
    }

    private val tableHeaderTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 14f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val lightGrayPaint = Paint().apply {
        color = Color.LTGRAY
        style = Paint.Style.FILL
    }

    // UPDATED: Now styled as normal body text (Black, larger size)
    private val timestampPaint = Paint().apply {
        color = Color.BLACK
        textSize = 14f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val borderPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private fun generateFileName(drugName: String, ndc: String): String {
        val safeDrugName = drugName.replace(" ", "_").replace("[^a-zA-Z0-9_]".toRegex(), "")
        return "DrugHistory_${safeDrugName}_${ndc}.pdf"
    }

    fun generateDrugHistoryPdf(
        drugName: String,
        totalCount: String,
        notes: String,
        ndc: String,
        expiry: String,
        lotNo: String,
        date: String,
        time: String
    ): File? {
        // 1. Define the Nested Folder Path
        val folderName = "PillReports/PillTransactionDetailReports"
        val directory = File(context.getExternalFilesDir(null), folderName)

        // 2. Create Directory if it doesn't exist
        if (!directory.exists()) {
            if (!directory.mkdirs()) {
                return null // Failed to create directory
            }
        }

        // 3. Define File
        val fileName = generateFileName(drugName, ndc)
        val file = File(directory, fileName)

        // Print this to Logcat
        logger.d("File Path: ${file.absolutePath}")
        logger.d("File Size: ${file.length()}")
        logger.d("Last Modified: ${Date(file.lastModified())}")

        // 4. STRICT CLEANUP: Delete existing file to ensure we write a fresh one
        if (file.exists()) {
            val deleted = file.delete()
            if (!deleted) {
                // Log warning: file might be locked by viewer, but we will try to overwrite anyway
                logger.w("Failed to delete existing file: $fileName")
            }
        }

        // --- Start PDF Generation ---
        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = pdfDocument.startPage(pageInfo)
        val canvas = page.canvas

        try {
            var yPosition = margin.toFloat()

            // Draw Title
            yPosition += 40
            canvas.drawText(
                context.getString(R.string.drug_history_details),
                (pageWidth / 2).toFloat(),
                yPosition,
                titlePaint
            )
            yPosition += 40

            // Draw Section Header
            canvas.drawText(
                "Drug Information",
                (pageWidth / 2).toFloat(),
                yPosition,
                headerPaint
            )
            yPosition += 30

            // Draw Table
            val tableWidth = pageWidth - (2 * margin)
            val column1Width = tableWidth * 0.4f
            val column2Width = tableWidth * 0.6f
            val startX = margin.toFloat()
            val rowHeight = 35f

            // Header Row
            drawTableRow(
                canvas,
                startX,
                yPosition,
                column1Width,
                column2Width,
                rowHeight,
                "Field",
                "Value",
                true
            )
            yPosition += rowHeight

            // Data Rows
            val rows = listOf(
                context.getString(R.string.drugname) to drugName,
                context.getString(R.string.total_count) to "$totalCount Pills",
                context.getString(R.string.ndc_gtin14).uppercase() to ndc,
                context.getString(R.string.expiry) to expiry,
                context.getString(R.string.lotNo) to lotNo,
                context.getString(R.string.date) to date,
                context.getString(R.string.time) to time
            )

            for ((label, value) in rows) {
                drawTableRow(
                    canvas,
                    startX,
                    yPosition,
                    column1Width,
                    column2Width,
                    rowHeight,
                    label,
                    value,
                    false
                )
                yPosition += rowHeight
            }

            yPosition += 30

            // Draw Notes Header
            canvas.drawText(
                context.getString(R.string.note),
                (pageWidth / 2).toFloat(),
                yPosition,
                headerPaint
            )
            yPosition += 20

            // Draw Notes Body (Wrapped)
            val notesTextPaint = TextPaint(cellTextPaint)
            val notesLayout = StaticLayout.Builder.obtain(
                notes, 0, notes.length, notesTextPaint, pageWidth - (2 * margin)
            )
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.0f)
                .setIncludePad(false)
                .build()

            // Check if notes fit on page, rudimentary check
            if (yPosition + notesLayout.height > pageHeight - margin) {
                // If notes are too long for one page, complex logic needed.
                // For this snippet, we assume single page or simple crop.
            }

            canvas.save()
            canvas.translate(margin.toFloat(), yPosition)
            notesLayout.draw(canvas)
            canvas.restore()

            yPosition += notesLayout.height + 40

            // Draw "Generated On" Text
            val footerText = "${context.getString(R.string.generated_on)} " +
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())

            canvas.drawText(footerText, (pageWidth / 2).toFloat(), yPosition, timestampPaint)

            pdfDocument.finishPage(page)

            // 5. Force Overwrite using explicit Mode
            val fileOutputStream = FileOutputStream(file, false) // false = overwrite
            pdfDocument.writeTo(fileOutputStream)
            fileOutputStream.close()

            return file

        } catch (e: IOException) {
            e.printStackTrace()
            try {
                pdfDocument.finishPage(page)
            } catch (ignored: Exception) {
            }
            return null
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            pdfDocument.close()
        }
    }

    private fun drawTableRow(
        canvas: Canvas,
        startX: Float,
        y: Float,
        col1Width: Float,
        col2Width: Float,
        height: Float,
        text1: String,
        text2: String,
        isHeader: Boolean
    ) {
        if (isHeader) {
            canvas.drawRect(
                startX,
                y,
                startX + col1Width + col2Width,
                y + height,
                tableHeaderBgPaint
            )
        } else {
            canvas.drawRect(startX, y, startX + col1Width, y + height, lightGrayPaint)
        }

        canvas.drawRect(startX, y, startX + col1Width, y + height, borderPaint)
        canvas.drawRect(
            startX + col1Width,
            y,
            startX + col1Width + col2Width,
            y + height,
            borderPaint
        )

        val textY = y + (height / 2) + 5
        val paint1 = if (isHeader) tableHeaderTextPaint else cellLabelPaint
        val paint2 = if (isHeader) tableHeaderTextPaint else cellTextPaint

        canvas.drawText(text1, startX + 10, textY, paint1)
        canvas.drawText(text2, startX + col1Width + 10, textY, paint2)
    }
}
