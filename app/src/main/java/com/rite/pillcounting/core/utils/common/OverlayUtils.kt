package com.rite.pillcounting.core.utils.common

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.scanning.domain.model.DetectedPill
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

object OverlayUtils {

    /**
     * How many of the [pillCount] on-tray pills are excess — drawn RED, meant to be
     * pushed into the chute. Only a dispense past the parent-container step has a
     * target to exceed: a stock count has none, and the parent-container step pours
     * out the whole stock bottle, so both keep every dot green.
     *
     * [alreadyCounted] pills from earlier in this session reduce the remaining target.
     * A [targetCount] of 0 means no target is known yet (the target dialog is still
     * open), which is different from a target that has been fully counted — the former
     * is all green, the latter makes every pill still on the tray excess.
     * On CONTAINER_PENDING [targetCount] is the expected leftover, so 0 is real there.
     *
     * @param isDispense From the transaction; false for a stock count, null before it loads.
     */
    fun excessPillCount(
        pillCount: Int,
        targetCount: Int,
        alreadyCounted: Int,
        isDispense: Boolean?,
        stepType: StepState,
    ): Int {
        if (isDispense != true) return 0
        if (stepType == StepState.CONTAINER_INITIATE) return 0
        // 0 means "no target yet", except on CONTAINER_PENDING where it means "bottle empty".
        if (targetCount <= 0 && stepType != StepState.CONTAINER_PENDING) return 0
        val remaining = (targetCount - alreadyCounted).coerceAtLeast(0)
        return (pillCount - remaining).coerceAtLeast(0)
    }

    fun drawDetectionsOnBitmap(
        bitmap: Bitmap,
        detectedPills: List<DetectedPill>,
        previewWidth: Int,
        previewHeight: Int,
        userName: String? = null,
        userId: String? = null,
        location: String? = null,
        ndc: String? = null,
        count: String? = null,
        patientId: String? = null,
        rx: String? = null,
        stepLabel: String? = null,
        lotNumber: String? = null,
        expirationDate: String? = null,
        serialNumber: String? = null,
        timestamp: Long = System.currentTimeMillis()
    ): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        val w = result.width.toFloat()
        val h = result.height.toFloat()
        val scale = min(w, h) / 1080f

        // ── Detect background brightness in the footer strip to pick a readable text colour ──
        val footerStripHeight = (160f * scale).toInt().coerceAtLeast(1)
        val stripY = (h - footerStripHeight).toInt().coerceAtLeast(0)
        val sampleW = result.width.coerceAtLeast(1)
        val sampleH = footerStripHeight.coerceAtMost(result.height - stripY)
        var luminanceSum = 0.0
        var pixelCount = 0
        val step = maxOf(1, sampleW / 20)
        for (x in 0 until sampleW step step) {
            for (y in stripY until stripY + sampleH step step) {
                val px = result.getPixel(x, y)
                val r = Color.red(px) / 255.0
                val g = Color.green(px) / 255.0
                val b = Color.blue(px) / 255.0
                luminanceSum += 0.2126 * r + 0.7152 * g + 0.0722 * b
                pixelCount++
            }
        }
        val avgLuminance = if (pixelCount > 0) luminanceSum / pixelCount else 0.0
        val onLight = avgLuminance > 0.5
        val textColor = if (onLight) Color.BLACK else Color.WHITE
        val shadowColor = if (onLight) Color.WHITE else Color.BLACK

        // ── Paint configuration ──────────────────────────────────────────────
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(180, 0, 0, 0)
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = 3f * scale
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 28f * scale
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
        }

        // ── Map normalised [0,1] pill coords → bitmap pixels ─────────────────
        val pts = FloatArray(detectedPills.size * 2)
        detectedPills.forEachIndexed { i, pill ->
            pts[i * 2] = pill.x * w
            pts[i * 2 + 1] = pill.y * h
        }
        for (i in pts.indices step 2) {
            pts[i] = pts[i].coerceIn(0f, w)
            pts[i + 1] = pts[i + 1].coerceIn(0f, h)
        }

        // ── Draw numbered circles ─────────────────────────────────────────────
        for (i in pts.indices step 2) {
            val cx = pts[i]
            val cy = pts[i + 1]
            val isLast = i / 2 == detectedPills.lastIndex

            val innerR = (if (isLast) 38f else 24f) * scale
            val outerR = (if (isLast) 34f else 20f) * scale
            val stroke = (if (isLast) 2f else 1f) * scale

            strokePaint.strokeWidth = stroke
            fillPaint.color = Color.argb(160, 0, 0, 0)
            textPaint.textSize = if (isLast) 36f * scale else 28f * scale

            canvas.drawCircle(cx, cy, innerR, fillPaint)
            canvas.drawCircle(cx, cy, outerR, strokePaint)

            val fm = textPaint.fontMetrics
            val offsetY = (fm.descent - fm.ascent) / 2 - fm.descent
            canvas.drawText("${i / 2 + 1}", cx, cy + offsetY, textPaint)
        }

        // ── Footer metadata (left-aligned, labeled) ───────────────────────────
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(timestamp))

        // Estimate JPEG size in KB (bitmap bytes compressed ~1:10 for typical camera frames)
        val rawBytes = result.byteCount
        val estimatedKb = rawBytes / 1024
        val imageSizeStr = "${result.width} × ${result.height}  (~${estimatedKb} KB)"

        // Build label-value rows (only non-blank values included)
        val rows = buildList<Pair<String, String>> {
            if (!ndc.isNullOrBlank()) add("NDC" to ndc!!)
            if (!rx.isNullOrBlank()) add("RX" to rx!!)
            if (!count.isNullOrBlank()) add("Count" to count!!)
            if (!patientId.isNullOrBlank()) add("Patient" to patientId!!)
            if (!lotNumber.isNullOrBlank()) add("Lot" to lotNumber!!)
            if (!expirationDate.isNullOrBlank()) add("Exp" to expirationDate!!)
            if (!serialNumber.isNullOrBlank()) add("Serial" to serialNumber!!)
            val nameStr = buildString {
                if (!userName.isNullOrBlank()) append(userName)
//                if (!userId.isNullOrBlank()) append(" ($userId)")
            }
            if (nameStr.isNotBlank()) add("Operator" to nameStr)
            if (!location.isNullOrBlank()) add("Location" to location!!)
            if (!stepLabel.isNullOrBlank()) add("Step" to stepLabel!!)
            add("Date" to dateStr)
            add("Size" to imageSizeStr)
        }

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = 20f * scale
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
            setShadowLayer(3f, 1f, 1f, shadowColor)
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = 20f * scale
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.LEFT
            setShadowLayer(3f, 1f, 1f, shadowColor)
        }

        val lineH = labelPaint.fontMetrics.run { descent - ascent } * 1.25f
        val leftMargin = 24f * scale
        val bottomPadding = 24f * scale

        // Start position: bottom-up
        val totalHeight = rows.size * lineH
        var drawY = h - bottomPadding - totalHeight + lineH

        // Semi-transparent background strip behind the footer text
        val bgPaint = Paint().apply {
            color = if (onLight) Color.argb(160, 255, 255, 255) else Color.argb(160, 0, 0, 0)
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, drawY - lineH, w, h, bgPaint)

        // Measure the widest label to align the colon column
        val colonGap = 8f * scale
        val labelWidth = rows.maxOf { (label, _) -> labelPaint.measureText("$label:") }

        for ((label, value) in rows) {
            canvas.drawText("$label:", leftMargin, drawY, labelPaint)
            canvas.drawText(value, leftMargin + labelWidth + colonGap, drawY, valuePaint)
            drawY += lineH
        }

        return result
    }
}
