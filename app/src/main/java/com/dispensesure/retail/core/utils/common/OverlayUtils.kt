package com.dispensesure.retail.core.utils.common

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import com.dispensesure.retail.R
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.scanning.domain.model.DetectedPill
import com.dispensesure.retail.core.scanning.logic.TrayClass
import com.dispensesure.retail.core.scanning.logic.TrayDetection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Values for the saved photo's info strips, already resolved to display text. */
data class PhotoInfo(
    val drugName: String?,
    val drugImage: Bitmap?,
    val isStockCount: Boolean,
    val requestedNdc: String?,
    val dispensedNdc: String?,
    val lotNumber: String?,
    val serialNumber: String?,
    val expirationDate: String?,
    val count: Int,
    val rxRefill: String?,
    val stepLabel: String?,
    val userName: String?,
    val location: String?,
    val timestamp: Long,
)

/** One label/value cell of an info strip. */
internal data class InfoField(val label: String, val value: String)

object OverlayUtils {

    /**
     * Number of on-tray pills drawn RED (excess, push into the chute) once the
     * remaining target ([targetCount] minus [alreadyCounted]) is exceeded.
     * Stock counts, the parent-container step and an unknown target return 0.
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

    /**
     * Saved-photo crop: the largest tray and chute boxes joined, grown 5% per side and
     * clamped to the frame. Null when either is missing or the crop is under 64 px.
     */
    fun savedPhotoCropRegion(trays: List<TrayDetection>, frameW: Int, frameH: Int): Rect? {
        val tray = trays.filter { it.cls == TrayClass.TRAY }
            .maxByOrNull { it.rect.width() * it.rect.height() } ?: return null
        val chute = trays.filter { it.cls == TrayClass.CHUTE }
            .maxByOrNull { it.rect.width() * it.rect.height() } ?: return null
        val box = RectF(tray.rect).apply { union(chute.rect) }
        val mx = box.width() * SAVED_CROP_MARGIN
        val my = box.height() * SAVED_CROP_MARGIN
        val region = Rect(
            floor(box.left - mx).toInt().coerceIn(0, frameW),
            floor(box.top - my).toInt().coerceIn(0, frameH),
            ceil(box.right + mx).toInt().coerceIn(0, frameW),
            ceil(box.bottom + my).toInt().coerceIn(0, frameH)
        )
        return if (region.width() >= SAVED_CROP_MIN_SIDE && region.height() >= SAVED_CROP_MIN_SIDE) region else null
    }

    /** Moves normalised frame positions into normalised [crop] positions. */
    fun pillsInCrop(pills: List<DetectedPill>, crop: Rect, frameW: Int, frameH: Int): List<DetectedPill> =
        pills.map {
            it.copy(
                x = (it.x * frameW - crop.left) / crop.width(),
                y = (it.y * frameH - crop.top) / crop.height()
            )
        }

    /** "3X-1", or "3X" without a refill; null without an Rx. */
    fun rxRefill(rxNo: String?, refillNo: String?): String? = when {
        rxNo.isNullOrBlank() -> null
        refillNo.isNullOrBlank() -> rxNo
        else -> "$rxNo-$refillNo"
    }

    /** Top-strip fields under the drug name; stock counts have a single NDC. */
    internal fun topRowFields(info: PhotoInfo, res: Resources): List<InfoField> = buildList {
        if (info.isStockCount) {
            add(InfoField(res.getString(R.string.ndc), info.requestedNdc.orDash()))
        } else {
            add(InfoField(res.getString(R.string.photo_label_requested_ndc), info.requestedNdc.orDash()))
            add(InfoField(res.getString(R.string.photo_label_dispensed_ndc), info.dispensedNdc.orDash()))
        }
        add(InfoField(res.getString(R.string.lotNo), info.lotNumber.orDash()))
        add(InfoField(res.getString(R.string.serial_no), info.serialNumber.orDash()))
        add(InfoField(res.getString(R.string.expiry), info.expirationDate.orDash()))
    }

    /**
     * Bottom-strip rows: four columns (stock counts have no Rx, so three), then User and
     * Location at half width.
     */
    internal fun bottomRows(info: PhotoInfo, res: Resources): List<List<InfoField>> {
        val date = SimpleDateFormat("MM-dd-yyyy HH:mm", Locale.getDefault()).format(Date(info.timestamp))
        return listOf(
            listOfNotNull(
                InfoField(res.getString(R.string.count), info.count.toString()),
                if (info.isStockCount) null
                else InfoField(res.getString(R.string.photo_label_rx_refill), info.rxRefill.orDash()),
                InfoField(res.getString(R.string.photo_label_step), info.stepLabel.orDash()),
                InfoField(res.getString(R.string.photo_label_date_time), date),
            ),
            listOf(
                InfoField(res.getString(R.string.photo_label_operator), info.userName.orDash()),
                InfoField(res.getString(R.string.location), info.location.orDash()),
            ),
        )
    }

    private fun String?.orDash(): String = if (isNullOrBlank()) "-" else this

    /**
     * Saved-photo layout: drug-details strip, [bitmap] with numbered circles and a count
     * badge, session-details strip. [scaleRefSide] sets the drawing size; defaults to the
     * bitmap's shorter side. [isPhone] uses smaller strip values. [res] supplies the labels.
     */
    fun drawDetectionsOnBitmap(
        bitmap: Bitmap,
        detectedPills: List<DetectedPill>,
        info: PhotoInfo,
        res: Resources,
        scaleRefSide: Int? = null,
        isPhone: Boolean = false
    ): Bitmap {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val scale = (scaleRefSide ?: min(bitmap.width, bitmap.height)) / 1080f
        // Not `style`: it would shadow Paint.style inside the apply blocks below.
        val stripStyle = StripStyle(scale, isPhone)

        val topStrip = layoutTopStrip(info, res, w, stripStyle)
        val bottomStrip = layoutBottomStrip(info, res, w, stripStyle)
        val photoTop = topStrip.height.toFloat()

        val result = Bitmap.createBitmap(
            bitmap.width, topStrip.height + bitmap.height + bottomStrip.height, Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap, 0f, photoTop, null)

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
            val cy = pts[i + 1] + photoTop
            val isLast = i / 2 == detectedPills.lastIndex

            val innerR = (if (isLast) 12f else 9f) * scale
            val outerR = (if (isLast) 10f else 7f) * scale
            val stroke = (if (isLast) 2f else 1f) * scale

            strokePaint.strokeWidth = stroke
            fillPaint.color = Color.argb(160, 0, 0, 0)
            textPaint.textSize = if (isLast) 9f * scale else 7f * scale

            canvas.drawCircle(cx, cy, innerR, fillPaint)
            canvas.drawCircle(cx, cy, outerR, strokePaint)

            val fm = textPaint.fontMetrics
            val offsetY = (fm.descent - fm.ascent) / 2 - fm.descent
            canvas.drawText("${i / 2 + 1}", cx, cy + offsetY, textPaint)
        }

        drawCountBadge(canvas, info.count, w, photoTop + h, scale)
        info.drugImage?.takeIf { !it.isRecycled }?.let { drawThumbnail(canvas, it, stripStyle) }
        topStrip.draw(canvas, 0f)
        bottomStrip.draw(canvas, photoTop + h)
        return result
    }

    /** Sizes and paints for the info strips, in 1080-reference units. Phones: all values 16. */
    private class StripStyle(scale: Float, isPhone: Boolean) {
        val pad = 24f * scale
        val gapX = 16f * scale
        val gapY = 16f * scale
        val labelGap = 2f * scale
        val thumbW = 100f * scale
        val thumbH = 70f * scale
        val thumbRadius = 8f * scale
        val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = STRIP_LABEL_GREY
            textSize = 18f * scale
            typeface = Typeface.SANS_SERIF
        }
        val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = (if (isPhone) 16f else 21f) * scale
            typeface = Typeface.SANS_SERIF
        }
        val namePaint = TextPaint(valuePaint).apply { textSize = (if (isPhone) 16f else 23f) * scale }
    }

    private class PlacedText(val layout: StaticLayout, val x: Float, val y: Float)

    /** A laid-out strip; [draw] paints its text with the strip's top at [top]. */
    private class Strip(val height: Int, private val texts: List<PlacedText>) {
        fun draw(canvas: Canvas, top: Float) {
            for (t in texts) {
                canvas.save()
                canvas.translate(t.x, top + t.y)
                t.layout.draw(canvas)
                canvas.restore()
            }
        }
    }

    private fun wrapped(text: String, paint: TextPaint, width: Int, maxLines: Int = Int.MAX_VALUE): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setMaxLines(maxLines)
            .build()

    /** Drug name: label and value wrap within [width]; returns the height. */
    private fun layoutName(
        field: InfoField, x: Float, width: Float, y0: Float,
        style: StripStyle, out: MutableList<PlacedText>
    ): Float {
        val w = width.toInt().coerceAtLeast(1)
        val label = wrapped(field.label, style.labelPaint, w)
        val value = wrapped(field.value, style.namePaint, w)
        out += PlacedText(label, x, y0)
        out += PlacedText(value, x, y0 + label.height + style.labelGap)
        return label.height + style.labelGap + value.height
    }

    /**
     * Lays [fields] out on one line each: columns sized to their longest text, spare width
     * shared out, and the whole row's text shrunk only when it can't fit. Returns the row height.
     */
    private fun layoutRow(
        fields: List<InfoField>, x0: Float, width: Float, y0: Float,
        style: StripStyle, valuePaint: TextPaint, out: MutableList<PlacedText>
    ): Float {
        val room = width - style.gapX * (fields.size - 1)
        val natural = fields.map { max(style.labelPaint.measureText(it.label), valuePaint.measureText(it.value)) }
        val shrink = (room / natural.sum().coerceAtLeast(1f)).coerceAtMost(1f)
        val labelPaint = TextPaint(style.labelPaint).apply { textSize *= shrink }
        val rowValuePaint = TextPaint(valuePaint).apply { textSize *= shrink }
        val extra = (room - natural.sum() * shrink).coerceAtLeast(0f) / fields.size

        var x = x0
        var rowH = 0f
        fields.forEachIndexed { i, field ->
            val colW = natural[i] * shrink + extra
            // +1 px so rounding never pushes text onto a second line.
            val w = ceil(colW).toInt() + 1
            val label = wrapped(field.label, labelPaint, w, maxLines = 1)
            val value = wrapped(field.value, rowValuePaint, w, maxLines = 1)
            out += PlacedText(label, x, y0)
            out += PlacedText(value, x, y0 + label.height + style.labelGap)
            rowH = max(rowH, label.height + style.labelGap + value.height)
            x += colW + style.gapX
        }
        return rowH
    }

    /** Thumbnail with the drug name beside it; the NDC/lot row below both, full width. */
    private fun layoutTopStrip(info: PhotoInfo, res: Resources, width: Float, style: StripStyle): Strip {
        val out = mutableListOf<PlacedText>()
        val nameX = style.pad + style.thumbW + style.gapX
        val nameW = (width - nameX - style.pad).coerceAtLeast(1f)
        val nameField = InfoField(res.getString(R.string.drug_name), info.drugName.orDash())
        val nameH = layoutName(nameField, nameX, nameW, style.pad, style, out)
        val rowY = style.pad + max(style.thumbH, nameH) + style.gapY
        val rowW = (width - style.pad * 2).coerceAtLeast(1f)
        val rowH = layoutRow(topRowFields(info, res), style.pad, rowW, rowY, style, style.valuePaint, out)
        return Strip(ceil(rowY + rowH + style.pad).toInt(), out)
    }

    private fun layoutBottomStrip(info: PhotoInfo, res: Resources, width: Float, style: StripStyle): Strip {
        val out = mutableListOf<PlacedText>()
        val textW = (width - style.pad * 2).coerceAtLeast(1f)
        var y = style.pad
        bottomRows(info, res).forEachIndexed { i, row ->
            if (i > 0) y += style.gapY
            y += layoutRow(row, style.pad, textW, y, style, style.valuePaint, out)
        }
        return Strip(ceil(y + style.pad).toInt(), out)
    }

    /** Drug image, centre-cropped into the rounded thumbnail box. */
    private fun drawThumbnail(canvas: Canvas, image: Bitmap, style: StripStyle) {
        val dst = RectF(style.pad, style.pad, style.pad + style.thumbW, style.pad + style.thumbH)
        val s = max(dst.width() / image.width, dst.height() / image.height)
        val cw = (dst.width() / s).toInt().coerceIn(1, image.width)
        val ch = (dst.height() / s).toInt().coerceIn(1, image.height)
        val src = Rect((image.width - cw) / 2, (image.height - ch) / 2, (image.width + cw) / 2, (image.height + ch) / 2)
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(dst, style.thumbRadius, style.thumbRadius, Path.Direction.CW) })
        canvas.drawBitmap(image, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
    }

    /** Dark rounded count box in the photo's bottom-right corner. */
    private fun drawCountBadge(canvas: Canvas, count: Int, photoRight: Float, photoBottom: Float, scale: Float) {
        val text = count.toString()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 56f * scale
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val fm = paint.fontMetrics
        val boxW = paint.measureText(text) + 40f * scale
        val boxH = (fm.descent - fm.ascent) + 24f * scale
        val box = RectF(
            photoRight - 24f * scale - boxW, photoBottom - 24f * scale - boxH,
            photoRight - 24f * scale, photoBottom - 24f * scale
        )
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BADGE_BG }
        canvas.drawRoundRect(box, 16f * scale, 16f * scale, bg)
        canvas.drawText(text, box.centerX(), box.centerY() - (fm.ascent + fm.descent) / 2, paint)
    }

    private const val SAVED_CROP_MARGIN = 0.05f
    private const val SAVED_CROP_MIN_SIDE = 64
    private val STRIP_LABEL_GREY = Color.rgb(0x75, 0x75, 0x75)
    private val BADGE_BG = Color.argb(200, 40, 40, 40)
}
