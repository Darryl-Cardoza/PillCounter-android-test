package com.rite.pillcounting.feature.history.presentation.compose

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.rite.pillcounting.R
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.models.dtos.TxnDetailInfo
import com.rite.pillcounting.core.room.models.dtos.TxnWithDetails
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DrugHistoryDetailPdfExporter(private val context: Context) {

    private val pageW = 595
    private val pageH = 842
    private val mH = 40f

    // ── Palette ───────────────────────────────────────────────────────────────
    private val cBlue      = Color.parseColor("#2563EB")
    private val cBlueDark = Color.parseColor("#1E3A8A")
    private val cGreen     = Color.parseColor("#059669")
    private val cSurface   = Color.parseColor("#F8FAFC")
    private val cLgray     = Color.parseColor("#F1F5F9")
    private val cDivider   = Color.parseColor("#E2E8F0")
    private val cTextDark = Color.parseColor("#0F172A")
    private val cTextMed  = Color.parseColor("#64748B")
    private val cTextLight= Color.parseColor("#94A3B8")

    // ── Row / card heights ───────────────────────────────────────────────────
    private val rowSectionHdr = 36f
    private val rowKv          = 26f
    private val rowCountHero  = 52f
    private val rowTblHdr     = 26f
    private val rowBatch       = 24f
    private val rowTotal       = 28f
    private val cardPadBot    = 14f

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

    // ── Page-break helpers ────────────────────────────────────────────────────

    private inner class Ctx(val pdf: PdfDocument) {
        var pageNum = 1
        var page: PdfDocument.Page = newPage()
        var cv: Canvas = page.canvas
        var y = 0f

        private fun newPage() = pdf.startPage(
            PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create()
        )

        fun need(height: Float) {
            if (y + height > pageH - 50f) {
                drawFooter(cv, pageNum); drawWatermark(cv)
                pdf.finishPage(page)
                pageNum++
                page = newPage(); cv = page.canvas
                y = 28f
            }
        }

        fun close() {
            drawFooter(cv, pageNum); drawWatermark(cv)
            pdf.finishPage(page)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun generate(txn: TxnWithDetails, userName: String): File? {
        val dir = File(context.getExternalFilesDir(null), "PillReports/PillTransactionDetailReports")
        if (!dir.exists() && !dir.mkdirs()) return null

        val safe = txn.drugName
            ?.replace(" ", "_")?.replace("[^a-zA-Z0-9_]".toRegex(), "") ?: "Drug"
        val file = File(dir, "DrugDetail_${safe}_${txn.ndc}_${System.currentTimeMillis()}.pdf")

        val pdf = PdfDocument()
        return try {
            val fmt      = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
            val genTime  = fmt.format(Date())
            val txnDate  = fmt.format(Date(txn.createdAt))
            val dateOnly = txnDate.substringBefore(",")
            val timeOnly = txnDate.substringAfter(", ")

            val ctx = Ctx(pdf)
            ctx.y = drawHeader(ctx.cv, genTime)
            ctx.y = drawInfoLine(ctx.cv, ctx.y, txn.drugName ?: "—", txnDate, userName)

            val verCount = txn.txnDetails.sumForStep(StepState.TARGET_VERIFICATION)
            ctx.y = drawSummaryTile(ctx.cv, ctx.y + 12f, verCount, txn.targetCount)
            ctx.y += 16f

            val isBatchFlow = txn.drugType != null && txn.drugType.toString() != "null"

            if (!isBatchFlow) {
                if (txn.txnDetails.hasStep(StepState.TARGET_VERIFICATION)) {
                    ctx.y = drawCountCard(ctx, context.getString(R.string.pill_count),
                        txn.txnDetails.forStep(StepState.TARGET_VERIFICATION), isVial = false)
                    ctx.y += 10f
                }
                if (txn.isSubstitute) {
                    ctx.y = drawKvCard(ctx, context.getString(R.string.requested_drug_details), listOf(
                        context.getString(R.string.drug_name) to (txn.requestedDrugName ?: "—"),
                        context.getString(R.string.pdf_ndc_label) to (txn.requestedNdc ?: "—"),
                    ))
                    ctx.y += 10f
                }
                val drugTitle = if (txn.isSubstitute)
                    context.getString(R.string.substitute_drug_details)
                else
                    context.getString(R.string.dispense_drug_details)
                ctx.y = drawKvCard(ctx, drugTitle, drugKvRows(txn, dateOnly, timeOnly))
                ctx.y += 10f

                if (txn.txnDetails.hasStep(StepState.VIAL)) {
                    ctx.y = drawCountCard(ctx, context.getString(R.string.vial_capture),
                        txn.txnDetails.forStep(StepState.VIAL), isVial = true)
                    ctx.y += 10f
                }
            } else {
                if (txn.txnDetails.hasStep(StepState.CONTAINER_INITIATE)) {
                    ctx.y = drawCountCard(ctx, context.getString(R.string.initial_stock_bottle_count),
                        txn.txnDetails.forStep(StepState.CONTAINER_INITIATE), isVial = false)
                    ctx.y += 10f
                }
                if (txn.isSubstitute) {
                    ctx.y = drawKvCard(ctx, context.getString(R.string.requested_drug_details), listOf(
                        context.getString(R.string.drug_name) to (txn.requestedDrugName ?: "—"),
                        context.getString(R.string.pdf_ndc_label) to (txn.requestedNdc ?: "—"),
                    ))
                    ctx.y += 10f
                }
                val drugTitle = if (txn.isSubstitute)
                    context.getString(R.string.substitute_drug_details)
                else
                    context.getString(R.string.dispense_drug_details)
                ctx.y = drawKvCard(ctx, drugTitle, drugKvRows(txn, dateOnly, timeOnly))
                ctx.y += 10f

                if (txn.txnDetails.hasStep(StepState.TARGET_VERIFICATION)) {
                    ctx.y = drawCountCard(ctx, context.getString(R.string.pill_count),
                        txn.txnDetails.forStep(StepState.TARGET_VERIFICATION), isVial = false)
                    ctx.y += 10f
                }
                if (txn.txnDetails.hasStep(StepState.TARGET_REVERIFICATION)) {
                    ctx.y = drawCountCard(ctx, context.getString(R.string.pill_recount),
                        txn.txnDetails.forStep(StepState.TARGET_REVERIFICATION), isVial = false)
                    ctx.y += 10f
                }
                if (txn.txnDetails.hasStep(StepState.VIAL)) {
                    ctx.y = drawCountCard(ctx, context.getString(R.string.vial_capture),
                        txn.txnDetails.forStep(StepState.VIAL), isVial = true)
                    ctx.y += 10f
                }
                if (txn.txnDetails.hasStep(StepState.CONTAINER_PENDING)) {
                    ctx.y = drawCountCard(ctx, context.getString(R.string.remaining_stock_bottle_count),
                        txn.txnDetails.forStep(StepState.CONTAINER_PENDING), isVial = false)
                    ctx.y += 10f
                }
            }

            ctx.y = drawNotesCard(ctx, txn.note ?: "")

            ctx.close()
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
        cv.drawRect(0f, 0f, pageW.toFloat(), h, fp(cBlue))
        cv.drawRect(0f, h - 3f, pageW.toFloat(), h, fp(cBlueDark))

        val cx = mH + 22f; val cy = h / 2f
        fp(Color.WHITE).also { it.alpha = 20 }.let { cv.drawCircle(cx, cy, 22f, it) }
        sp(Color.WHITE, 1.5f).also { it.alpha = 100 }.let { cv.drawCircle(cx, cy, 22f, it) }
        bp(Color.WHITE, 14f, Paint.Align.CENTER).let {
            cv.drawText("PC", cx, cy - (it.ascent() + it.descent()) / 2f, it)
        }

        val nx = mH + 52f
        cv.drawText(context.getString(R.string.pill_count_app_title), nx, h * 0.40f, bp(Color.WHITE, 19f))
        np(Color.WHITE, 8.5f).also { it.alpha = 170 }.let {
            cv.drawText(context.getString(R.string.pdf_developer_credit), nx, h * 0.66f, it)
        }
        bp(Color.WHITE, 12f, Paint.Align.RIGHT).also { it.alpha = 220 }.let {
            cv.drawText(context.getString(R.string.pdf_drug_detail_report_title), pageW - mH, h * 0.37f, it)
        }
        np(Color.WHITE, 8f, Paint.Align.RIGHT).also { it.alpha = 150 }.let {
            cv.drawText("${context.getString(R.string.pdf_generated_label)} $genTime", pageW - mH, h * 0.62f, it)
        }
        return h
    }

    // ── Info line ─────────────────────────────────────────────────────────────

    private fun drawInfoLine(cv: Canvas, top: Float, drugName: String, txnDate: String, userName: String): Float {
        val h  = 62f
        val lx = mH
        val rx = pageW - mH

        // Left: drug name + date
        val displayName = if (drugName.length > 44) drugName.take(42) + "…" else drugName
        cv.drawText(displayName, lx, top + 22f, bp(cTextDark, 13f))
        cv.drawText(txnDate, lx, top + 42f, np(cTextMed, 9f))

        // Right: "PREPARED BY" label + username value
        val truncUser = if (userName.length > 28) userName.take(26) + "…" else userName
        bp(cTextLight, 7.5f, Paint.Align.RIGHT).let {
            cv.drawText(context.getString(R.string.pdf_prepared_by_label), rx, top + 18f, it)
        }
        bp(cTextDark, 11f, Paint.Align.RIGHT).let {
            cv.drawText(truncUser, rx, top + 36f, it)
        }

        cv.drawLine(mH, top + h, pageW - mH, top + h, sp(cDivider))
        return top + h
    }

    // ── Summary tile (total count + target) ───────────────────────────────────

    private fun drawSummaryTile(cv: Canvas, top: Float, count: Int, target: Int?): Float {
        val h = 76f

        if (target != null) {
            // Two tiles: Counted | Target
            val gap = 10f
            val mid = pageW / 2f

            fun tile(rect: RectF, label: String, value: String, lx: Float, align: Paint.Align) {
                cv.drawRoundRect(rect, 8f, 8f, fp(cLgray))
                cv.drawRoundRect(rect, 8f, 8f, sp(cDivider))
                cv.drawRoundRect(RectF(rect.left, rect.top, rect.right, rect.top + 3f), 8f, 8f, fp(cBlue))
                cv.drawText(label, lx, rect.top + 20f, bp(cTextMed, 8f, align))
                cv.drawText(value, lx, rect.bottom - 10f, bp(cGreen, 32f, align))
            }

            tile(RectF(mH, top, mid - gap / 2f, top + h),
                context.getString(R.string.pdf_counted_label), count.toString(), mH + 14f, Paint.Align.LEFT)
            tile(RectF(mid + gap / 2f, top, pageW - mH, top + h),
                context.getString(R.string.pdf_target_label), target.toString(), pageW - mH - 14f, Paint.Align.RIGHT)
        } else {
            // Single wide tile
            val rect = RectF(mH, top, pageW - mH, top + h)
            cv.drawRoundRect(rect, 8f, 8f, fp(cLgray))
            cv.drawRoundRect(rect, 8f, 8f, sp(cDivider))
            cv.drawRoundRect(RectF(rect.left, rect.top, rect.right, rect.top + 3f), 8f, 8f, fp(cBlue))
            cv.drawText(context.getString(R.string.pdf_total_count_label), mH + 14f, rect.top + 20f, bp(cTextMed, 8f))
            cv.drawText(count.toString(), mH + 14f, rect.bottom - 10f, bp(cGreen, 32f))
        }
        return top + h
    }

    // ── Key-value card ────────────────────────────────────────────────────────

    private fun kvCardHeight(rows: List<Pair<String, String>>) =
        rowSectionHdr + rows.size * rowKv + cardPadBot

    private fun drawKvCard(ctx: Ctx, title: String, rows: List<Pair<String, String>>): Float {
        val h = kvCardHeight(rows)
        ctx.need(h)
        return drawKvCardAt(ctx.cv, ctx.y, title, rows).also { ctx.y = it }
    }

    private fun drawKvCardAt(cv: Canvas, top: Float, title: String, rows: List<Pair<String, String>>): Float {
        val h     = kvCardHeight(rows)
        val left  = mH; val right = pageW - mH
        val ix    = left + 20f

        cv.drawRoundRect(RectF(left, top, right, top + h), 10f, 10f, fp(Color.WHITE))
        cv.drawRoundRect(RectF(left, top, right, top + h), 10f, 10f, sp(cDivider))
        cv.drawRoundRect(RectF(left, top + 10f, left + 4f, top + h - 10f), 2f, 2f, fp(cBlue))

        // Section header bar
        cv.drawRoundRect(RectF(left, top, right, top + rowSectionHdr), 10f, 10f, fp(cLgray))
        cv.drawLine(left, top + rowSectionHdr, right, top + rowSectionHdr, sp(cDivider))
        bp(cTextDark, 11f).also { it.letterSpacing = 0.04f }.let {
            cv.drawText(title, ix, bl(top, rowSectionHdr, it), it)
        }

        var y = top + rowSectionHdr
        val labelW = (right - left) * 0.38f

        rows.forEachIndexed { i, (key, value) ->
            if (i % 2 == 1) cv.drawRect(left + 4f, y, right, y + rowKv, fp(cSurface))
            cv.drawText(key,   ix,          bl(y, rowKv, np(cTextMed, 10f)), np(cTextMed, 10f))
            cv.drawText(value, ix + labelW, bl(y, rowKv, bp(cTextDark, 10.5f)), bp(cTextDark, 10.5f))
            y += rowKv
            if (i < rows.lastIndex) cv.drawLine(left + 4f, y, right, y, sp(cLgray, 0.5f))
        }

        return top + h
    }

    // ── Count card (batch tray / vial table) ──────────────────────────────────

    private fun countCardHeight(batches: List<TxnDetailInfo>): Float {
        val tableH = if (batches.isNotEmpty())
            rowTblHdr + batches.size * rowBatch + rowTotal
        else rowBatch
        return rowSectionHdr + rowCountHero + tableH + cardPadBot
    }

    private fun drawCountCard(ctx: Ctx, title: String, batches: List<TxnDetailInfo>, isVial: Boolean): Float {
        val h = countCardHeight(batches)
        ctx.need(h)
        return drawCountCardAt(ctx.cv, ctx.y, title, batches, isVial).also { ctx.y = it }
    }

    private fun drawCountCardAt(cv: Canvas, top: Float, title: String,
                                batches: List<TxnDetailInfo>, isVial: Boolean): Float {
        val h     = countCardHeight(batches)
        val left  = mH; val right = pageW - mH
        val ix    = left + 20f; val irx = right - 16f
        val total = batches.sumOf { it.pillCount ?: 0 }

        cv.drawRoundRect(RectF(left, top, right, top + h), 10f, 10f, fp(Color.WHITE))
        cv.drawRoundRect(RectF(left, top, right, top + h), 10f, 10f, sp(cDivider))
        cv.drawRoundRect(RectF(left, top + 10f, left + 4f, top + h - 10f), 2f, 2f, fp(cBlue))

        // Section header
        cv.drawRoundRect(RectF(left, top, right, top + rowSectionHdr), 10f, 10f, fp(cLgray))
        cv.drawLine(left, top + rowSectionHdr, right, top + rowSectionHdr, sp(cDivider))
        bp(cTextDark, 11f).also { it.letterSpacing = 0.04f }.let {
            cv.drawText(title, ix, bl(top, rowSectionHdr, it), it)
        }

        // Hero count
        var y = top + rowSectionHdr
        if (!isVial) {
            bp(cGreen, 28f, Paint.Align.RIGHT).let { cv.drawText(total.toString(), irx, bl(y, rowCountHero, it), it) }
            np(cTextMed, 9f).let {
                cv.drawText(context.getString(R.string.pdf_total_count_label), ix, bl(y, rowCountHero, it), it)
            }
        } else {
            np(cTextMed, 9f).let {
                cv.drawText(context.getString(R.string.pdf_images_captured, batches.size), ix, bl(y, rowCountHero, it), it)
            }
        }
        y += rowCountHero
        cv.drawLine(left + 4f, y, right, y, sp(cDivider))

        // Batch table
        if (batches.isNotEmpty()) {
            val tL = ix; val tR = irx; val tW = tR - tL
            val c1W = tW * 0.15f; val c2W = tW * 0.70f

            // Table header
            cv.drawRect(tL, y, tR, y + rowTblHdr, fp(cLgray))
            bp(cTextLight, 8.5f).let { cv.drawText("#", tL + 4f, bl(y, rowTblHdr, it), it) }
            bp(cTextLight, 8.5f, Paint.Align.CENTER).let {
                cv.drawText(context.getString(R.string.batch), tL + c1W + c2W / 2f, bl(y, rowTblHdr, it), it)
            }
            if (!isVial) bp(cTextLight, 8.5f, Paint.Align.RIGHT).let {
                cv.drawText(context.getString(R.string.count), tR, bl(y, rowTblHdr, it), it)
            }
            y += rowTblHdr

            batches.forEachIndexed { idx, batch ->
                if (idx % 2 == 1) cv.drawRect(tL, y, tR, y + rowBatch, fp(cSurface))
                np(cTextDark, 10f).let { cv.drawText("${idx + 1}", tL + 4f, bl(y, rowBatch, it), it) }
                np(cTextMed, 9.5f, Paint.Align.CENTER).let {
                    cv.drawText(context.getString(R.string.pdf_batch_entry, idx + 1), tL + c1W + c2W / 2f, bl(y, rowBatch, it), it)
                }
                if (!isVial) np(cTextDark, 10f, Paint.Align.RIGHT).let {
                    cv.drawText((batch.pillCount ?: 0).toString(), tR, bl(y, rowBatch, it), it)
                }
                y += rowBatch
                cv.drawLine(tL, y, tR, y, sp(cLgray, 0.5f))
            }

            if (!isVial) {
                cv.drawRect(tL, y, tR, y + rowTotal, fp(cSurface))
                bp(cTextDark, 10.5f).let {
                    cv.drawText(context.getString(R.string.total), tL + 4f, bl(y, rowTotal, it), it)
                }
                bp(cGreen, 10.5f, Paint.Align.RIGHT).let {
                    cv.drawText(total.toString(), tR, bl(y, rowTotal, it), it)
                }
                y += rowTotal
            }
        } else {
            np(cTextLight, 9.5f).let {
                cv.drawText(context.getString(R.string.no_batches_recorded), ix, bl(y, rowBatch, it), it)
            }
            y += rowBatch
        }

        return top + h
    }

    // ── Notes card (wrapping text) ────────────────────────────────────────────

    private fun drawNotesCard(ctx: Ctx, note: String): Float {
        val contentW  = (pageW - 2 * mH - 24f).toInt()
        val textPaint = TextPaint().apply {
            color = cTextDark; textSize = 11f; isAntiAlias = true
        }
        val displayNote = note.ifBlank { "—" }
        val layout = StaticLayout.Builder
            .obtain(displayNote, 0, displayNote.length, textPaint, contentW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(2f, 1f)
            .setIncludePad(false)
            .build()

        val cardH = rowSectionHdr + cardPadBot + layout.height + cardPadBot
        ctx.need(cardH)

        val top  = ctx.y
        val left = mH; val right = pageW - mH
        val ix   = left + 20f

        ctx.cv.drawRoundRect(RectF(left, top, right, top + cardH), 10f, 10f, fp(Color.WHITE))
        ctx.cv.drawRoundRect(RectF(left, top, right, top + cardH), 10f, 10f, sp(cDivider))
        ctx.cv.drawRoundRect(RectF(left, top + 10f, left + 4f, top + cardH - 10f), 2f, 2f, fp(cBlue))

        ctx.cv.drawRoundRect(RectF(left, top, right, top + rowSectionHdr), 10f, 10f, fp(cLgray))
        ctx.cv.drawLine(left, top + rowSectionHdr, right, top + rowSectionHdr, sp(cDivider))
        bp(cTextDark, 11f).also { it.letterSpacing = 0.04f }.let {
            ctx.cv.drawText(context.getString(R.string.notes), ix, bl(top, rowSectionHdr, it), it)
        }

        ctx.cv.save()
        ctx.cv.translate(ix, top + rowSectionHdr + cardPadBot)
        layout.draw(ctx.cv)
        ctx.cv.restore()

        ctx.y = top + cardH
        return ctx.y
    }

    // ── Footer ────────────────────────────────────────────────────────────────

    private fun drawFooter(cv: Canvas, pageNum: Int) {
        val lineY = pageH - 28f; val textY = pageH - 13f
        cv.drawLine(mH, lineY, pageW - mH, lineY, sp(cDivider))
        np(cTextLight, 7.5f).let {
            cv.drawText(context.getString(R.string.pdf_footer_drug_detail), mH, textY, it)
        }
        np(cTextLight, 7.5f, Paint.Align.RIGHT).let {
            cv.drawText(context.getString(R.string.pdf_page, pageNum), pageW - mH, textY, it)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun List<TxnDetailInfo>.forStep(step: StepState) = filter { it.type == step }
    private fun List<TxnDetailInfo>.hasStep(step: StepState) = any { it.type == step }
    private fun List<TxnDetailInfo>.sumForStep(step: StepState) = forStep(step).sumOf { it.pillCount ?: 0 }

    private fun drugKvRows(txn: TxnWithDetails, dateOnly: String, timeOnly: String) = listOf(
        context.getString(R.string.drug_name) to (txn.drugName ?: "—"),
        context.getString(R.string.pdf_ndc_label) to (txn.ndc ?: "—"),
        context.getString(R.string.date) to dateOnly,
        context.getString(R.string.time) to timeOnly,
    )
}
