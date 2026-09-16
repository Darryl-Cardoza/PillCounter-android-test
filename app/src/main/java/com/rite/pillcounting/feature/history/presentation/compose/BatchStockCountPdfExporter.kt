package com.rite.pillcounting.feature.history.presentation.compose

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.rite.pillcounting.R
import com.rite.pillcounting.feature.batchCount.domain.model.BatchDrugGroup
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BatchStockCountPdfExporter(private val context: Context) {

    private val pageW = 595
    private val pageH = 842
    private val mH    = 40f

    // ── Palette ───────────────────────────────────────────────────────────────
    private val C_BLUE       = Color.parseColor("#2563EB")
    private val C_BLUE_DARK  = Color.parseColor("#1E3A8A")
    private val C_GREEN      = Color.parseColor("#059669")
    private val C_AMBER      = Color.parseColor("#D97706")
    private val C_SURFACE    = Color.parseColor("#F8FAFC")
    private val C_LGRAY      = Color.parseColor("#F1F5F9")
    private val C_DIVIDER    = Color.parseColor("#E2E8F0")
    private val C_TEXT_DARK  = Color.parseColor("#0F172A")
    private val C_TEXT_MED   = Color.parseColor("#64748B")
    private val C_TEXT_LIGHT = Color.parseColor("#94A3B8")

    // ── Card row heights — must match between cardHeight() & drawDrugCard() ───
    private val ROW_DRUG_HDR = 60f
    private val ROW_SECTION  = 32f
    private val ROW_TBL_HDR  = 26f
    private val ROW_LOT      = 24f
    private val ROW_TOTAL    = 28f
    private val CARD_PAD_BOT = 14f

    // ── Paint helpers ─────────────────────────────────────────────────────────
    private fun bp(c: Int, sz: Float, a: Paint.Align = Paint.Align.LEFT) = Paint().apply {
        color = c; textSize = sz
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = a; isAntiAlias = true
    }
    private fun np(c: Int, sz: Float, a: Paint.Align = Paint.Align.LEFT) = Paint().apply {
        color = c; textSize = sz; textAlign = a; isAntiAlias = true
    }
    private fun fp(c: Int) = Paint().apply { color = c; style = Paint.Style.FILL; isAntiAlias = true }
    private fun sp(c: Int, w: Float = 0.8f) = Paint().apply {
        color = c; style = Paint.Style.STROKE; strokeWidth = w; isAntiAlias = true
    }
    private fun bl(rowTop: Float, rowH: Float, p: Paint) =
        rowTop + rowH / 2f - (p.ascent() + p.descent()) / 2f

    // ── Public API ────────────────────────────────────────────────────────────

    fun generate(
        batchId: Long,
        uniqueNdcCount: Int,
        drugGroups: List<BatchDrugGroup>,
        batchStatus: String,
        startDateTime: Long?,
        userName: String
    ): File? {
        val dir = File(context.getExternalFilesDir(null), "PillReports/StockCountReports")
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, "StockCount_Batch${batchId}_${System.currentTimeMillis()}.pdf")

        val pdf = PdfDocument()
        return try {
            val genTime   = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date())
            val batchDate = startDateTime?.takeIf { it > 0 }
                ?.let { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(it)) }
                ?: genTime.substringBefore(",")
            val totalPills   = drugGroups.sumOf { it.totalCount }
            val statusLabel  = if (batchStatus == "COMPLETED")
                context.getString(R.string.pdf_status_completed)
            else
                context.getString(R.string.pdf_status_in_progress)
            val statusColor  = if (batchStatus == "COMPLETED") C_GREEN else C_AMBER

            var pageNum = 1
            var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
            var cv = page.canvas

            var y = drawHeader(cv, genTime)
            y = drawInfoLine(cv, y, batchId, batchDate, statusLabel, statusColor, userName)
            y = drawSummaryTiles(cv, y + 16f, uniqueNdcCount, totalPills)
            y += 20f

            for (group in drugGroups) {
                val cH = cardHeight(group)
                if (y + cH > pageH - 42f) {
                    drawFooter(cv, pageNum)
                    drawWatermark(cv)
                    pdf.finishPage(page)
                    pageNum++
                    page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
                    cv = page.canvas
                    y = 28f
                }
                y = drawDrugCard(cv, y, group)
                y += 12f
            }

            drawFooter(cv, pageNum)
            drawWatermark(cv)
            pdf.finishPage(page)
            FileOutputStream(file).use { pdf.writeTo(it) }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            pdf.close()
        }
    }

    // ── Watermark ─────────────────────────────────────────────────────────────

    private fun drawWatermark(cv: Canvas) {
        cv.save()
        cv.rotate(-32f, pageW / 2f, pageH / 2f)
        bp(Color.parseColor("#DBEAFE"), 52f, Paint.Align.CENTER).also { it.alpha = 55 }.let {
            cv.drawText(context.getString(R.string.pill_count_app_title), pageW / 2f, pageH / 2f - 28f, it)
        }
        np(Color.parseColor("#DBEAFE"), 20f, Paint.Align.CENTER).also { it.alpha = 45 }.let {
            cv.drawText(context.getString(R.string.pdf_developer_credit), pageW / 2f, pageH / 2f + 18f, it)
        }
        cv.restore()
    }

    // ── Header ────────────────────────────────────────────────────────────────

    private fun drawHeader(cv: Canvas, genTime: String): Float {
        val h = 78f

        cv.drawRect(0f, 0f, pageW.toFloat(), h, fp(C_BLUE))
        cv.drawRect(0f, h - 3f, pageW.toFloat(), h, fp(C_BLUE_DARK))

        // "PC" badge
        val cx = mH + 22f; val cy = h / 2f
        fp(Color.WHITE).also { it.alpha = 20 }.let { cv.drawCircle(cx, cy, 22f, it) }
        sp(Color.WHITE, 1.5f).also { it.alpha = 100 }.let { cv.drawCircle(cx, cy, 22f, it) }
        bp(Color.WHITE, 14f, Paint.Align.CENTER).let {
            cv.drawText("PC", cx, cy - (it.ascent() + it.descent()) / 2f, it)
        }

        // Brand name
        val nx = mH + 52f
        cv.drawText(context.getString(R.string.pill_count_app_title), nx, h * 0.40f, bp(Color.WHITE, 19f))
        np(Color.WHITE, 8.5f).also { it.alpha = 170 }.let {
            cv.drawText(context.getString(R.string.pdf_developer_credit), nx, h * 0.66f, it)
        }

        // Report title (right)
        bp(Color.WHITE, 12f, Paint.Align.RIGHT).also { it.alpha = 220 }.let {
            cv.drawText(context.getString(R.string.pdf_stock_count_report_title), pageW - mH, h * 0.37f, it)
        }
        np(Color.WHITE, 8f, Paint.Align.RIGHT).also { it.alpha = 150 }.let {
            cv.drawText("${context.getString(R.string.pdf_generated_label)} $genTime", pageW - mH, h * 0.62f, it)
        }

        return h
    }

    // ── Info line ─────────────────────────────────────────────────────────────

    private fun drawInfoLine(
        cv: Canvas, top: Float,
        batchId: Long, date: String,
        statusLabel: String, statusColor: Int,
        userName: String
    ): Float {
        val h = 52f
        val lx = mH
        val lineY = top + h

        // Row 1: "Batch  #ID" bold + status pill
        val batchP = bp(C_TEXT_DARK, 13f)
        val batchText = context.getString(R.string.pdf_batch_id, batchId)
        cv.drawText(batchText, lx, top + 22f, batchP)

        // Status pill positioned after batch text
        val pillX = lx + batchP.measureText(batchText) + 12f
        val pillText = "  $statusLabel  "
        val pillP    = bp(statusColor, 8.5f)
        val pillW    = pillP.measureText(pillText)
        val pillTop  = top + 22f + batchP.ascent() - 2f
        val pillBot  = top + 22f + batchP.descent() + 2f
        fp(statusColor).also { it.alpha = 22 }.let {
            cv.drawRoundRect(RectF(pillX, pillTop, pillX + pillW, pillBot), 6f, 6f, it)
        }
        sp(statusColor, 0.8f).let {
            cv.drawRoundRect(RectF(pillX, pillTop, pillX + pillW, pillBot), 6f, 6f, it)
        }
        cv.drawText(pillText, pillX, top + 22f, pillP)

        // Row 2: date · prepared by
        val truncUser = if (userName.length > 30) userName.take(28) + "…" else userName
        np(C_TEXT_MED, 9f).let {
            cv.drawText("$date   ·   ${context.getString(R.string.pdf_prepared_by)} $truncUser", lx, top + 42f, it)
        }

        // Bottom separator
        cv.drawLine(mH, lineY, pageW - mH, lineY, sp(C_DIVIDER))

        return lineY
    }

    // ── Summary tiles ─────────────────────────────────────────────────────────

    private fun drawSummaryTiles(cv: Canvas, top: Float, ndcCount: Int, totalPills: Int): Float {
        val h   = 72f
        val gap = 10f
        val mid = pageW / 2f

        fun tile(rect: RectF, label: String, value: String, labelAlign: Paint.Align, valueAlign: Paint.Align, lx: Float) {
            cv.drawRoundRect(rect, 8f, 8f, fp(C_LGRAY))
            cv.drawRoundRect(rect, 8f, 8f, sp(C_DIVIDER))
            // accent top border
            cv.drawRoundRect(RectF(rect.left, rect.top, rect.right, rect.top + 3f), 8f, 8f, fp(C_BLUE))
            cv.drawText(label, lx, rect.top + 20f, bp(C_TEXT_MED, 8f, labelAlign))
            cv.drawText(value, lx, rect.bottom - 10f, bp(C_GREEN, 32f, valueAlign))
        }

        // Left tile
        val lr = RectF(mH, top, mid - gap / 2f, top + h)
        tile(lr, context.getString(R.string.total_ndc_count), ndcCount.toString(),
            Paint.Align.LEFT, Paint.Align.LEFT, mH + 14f)

        // Right tile
        val rr = RectF(mid + gap / 2f, top, pageW - mH, top + h)
        tile(rr, context.getString(R.string.total_pills).uppercase(), totalPills.toString(),
            Paint.Align.RIGHT, Paint.Align.RIGHT, pageW - mH - 14f)

        return top + h
    }

    // ── Drug card ─────────────────────────────────────────────────────────────

    private fun cardHeight(group: BatchDrugGroup): Float {
        val lotsH = if (group.sealedLots.isNotEmpty())
            ROW_TBL_HDR + group.sealedLots.size * ROW_LOT + ROW_TOTAL
        else 0f
        return ROW_DRUG_HDR + ROW_SECTION + lotsH + ROW_SECTION + CARD_PAD_BOT
    }

    private fun drawDrugCard(cv: Canvas, top: Float, group: BatchDrugGroup): Float {
        val h     = cardHeight(group)
        val left  = mH
        val right = pageW - mH
        val ix    = left + 20f
        val irx   = right - 16f

        // Card background
        cv.drawRoundRect(RectF(left, top, right, top + h), 10f, 10f, fp(Color.WHITE))
        cv.drawRoundRect(RectF(left, top, right, top + h), 10f, 10f, sp(C_DIVIDER))
        // Blue left accent
        cv.drawRoundRect(RectF(left, top + 10f, left + 4f, top + h - 10f), 2f, 2f, fp(C_BLUE))

        // Drug name + NDC + total count
        cv.drawText(group.drugName, ix, top + 24f, bp(C_TEXT_DARK, 13f))
        cv.drawText("${context.getString(R.string.pdf_ndc_label)}: ${group.ndc}", ix, top + 41f, np(C_TEXT_MED, 9.5f))
        bp(C_GREEN, 24f, Paint.Align.RIGHT).let { cv.drawText(group.totalCount.toString(), irx, top + 34f, it) }

        // Divider
        var y = top + ROW_DRUG_HDR
        cv.drawLine(left + 4f, y, right, y, sp(C_DIVIDER))

        // Section label / value paints (reused for Sealed + Opened)
        val secLbl = bp(C_TEXT_MED, 9f).also { it.letterSpacing = 0.06f }
        val secVal = bp(C_TEXT_DARK, 11f, Paint.Align.RIGHT)

        // SEALED BOTTLES
        cv.drawText(context.getString(R.string.sealed_bottles).uppercase(), ix, bl(y, ROW_SECTION, secLbl), secLbl)
        cv.drawText(group.sealedTotal.toString(), irx, bl(y, ROW_SECTION, secVal), secVal)
        y += ROW_SECTION

        // Lot table
        if (group.sealedLots.isNotEmpty()) {
            val tL  = ix
            val tR  = irx
            val tW  = tR - tL
            val c1W = tW * 0.38f
            val c2W = tW * 0.37f

            // Table header
            cv.drawRect(tL, y, tR, y + ROW_TBL_HDR, fp(C_LGRAY))
            bp(C_TEXT_LIGHT, 8.5f).let {
                cv.drawText(context.getString(R.string.lot_No), tL + 4f, bl(y, ROW_TBL_HDR, it), it)
            }
            bp(C_TEXT_LIGHT, 8.5f, Paint.Align.CENTER).let {
                cv.drawText(context.getString(R.string.expiry_date), tL + c1W + c2W / 2f, bl(y, ROW_TBL_HDR, it), it)
            }
            bp(C_TEXT_LIGHT, 8.5f, Paint.Align.RIGHT).let {
                cv.drawText(context.getString(R.string.pills), tR, bl(y, ROW_TBL_HDR, it), it)
            }
            y += ROW_TBL_HDR

            // Lot rows
            for (lot in group.sealedLots) {
                np(C_TEXT_DARK, 10f).let {
                    cv.drawText(lot.lotNo?.takeIf(String::isNotBlank) ?: "—", tL + 4f, bl(y, ROW_LOT, it), it)
                }
                np(C_TEXT_DARK, 10f, Paint.Align.CENTER).let {
                    cv.drawText(lot.expiry?.takeIf(String::isNotBlank) ?: "—", tL + c1W + c2W / 2f, bl(y, ROW_LOT, it), it)
                }
                np(C_TEXT_DARK, 10f, Paint.Align.RIGHT).let {
                    cv.drawText(lot.count.toString(), tR, bl(y, ROW_LOT, it), it)
                }
                y += ROW_LOT
                cv.drawLine(tL, y, tR, y, sp(C_LGRAY))
            }

            // Total row
            cv.drawRect(tL, y, tR, y + ROW_TOTAL, fp(C_SURFACE))
            bp(C_TEXT_DARK, 10.5f).let {
                cv.drawText(context.getString(R.string.total), tL + 4f, bl(y, ROW_TOTAL, it), it)
            }
            bp(C_TEXT_DARK, 10.5f, Paint.Align.RIGHT).let {
                cv.drawText(group.sealedTotal.toString(), tR, bl(y, ROW_TOTAL, it), it)
            }
            y += ROW_TOTAL
            cv.drawLine(left + 4f, y, right, y, sp(C_DIVIDER))
        }

        // OPENED BOTTLES
        cv.drawText(context.getString(R.string.opend_bottles).uppercase(), ix, bl(y, ROW_SECTION, secLbl), secLbl)
        cv.drawText(group.openedTotal.toString(), irx, bl(y, ROW_SECTION, secVal), secVal)

        return top + h
    }

    // ── Footer ────────────────────────────────────────────────────────────────

    private fun drawFooter(cv: Canvas, pageNum: Int) {
        val lineY = pageH - 28f
        val textY = pageH - 13f
        cv.drawLine(mH, lineY, pageW - mH, lineY, sp(C_DIVIDER))
        np(C_TEXT_LIGHT, 7.5f).let {
            cv.drawText(context.getString(R.string.pdf_footer_stock_count), mH, textY, it)
        }
        np(C_TEXT_LIGHT, 7.5f, Paint.Align.RIGHT).let {
            cv.drawText(context.getString(R.string.pdf_page, pageNum), pageW - mH, textY, it)
        }
    }
}
