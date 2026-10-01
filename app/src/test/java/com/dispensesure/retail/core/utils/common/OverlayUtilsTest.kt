package com.dispensesure.retail.core.utils.common

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import com.dispensesure.retail.R
import com.dispensesure.retail.core.scanning.domain.model.DetectedPill
import com.dispensesure.retail.core.scanning.logic.TrayClass
import com.dispensesure.retail.core.scanning.logic.TrayDetection
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Unit tests for [OverlayUtils.drawDetectionsOnBitmap].
 *
 * Runs under Robolectric because the function performs real Bitmap/Canvas/Paint drawing
 * (circle/text drawing) which cannot be meaningfully exercised
 * with plain mocks - the branch logic (strip layout, text wrapping, coordinate
 * clamping) depends on actual pixel values and bitmap dimensions. Robolectric's default
 * (LEGACY) graphics mode silently no-ops real Canvas drawing calls, so pixels never actually
 * change — NATIVE mode is required for the drawn-pixel assertions here to be meaningful.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayUtilsTest {

    // Unit tests have no app resources (see ShadowUnlinkedResources), so labels come from a stub.
    private val res: Resources = mockk {
        val labels = mapOf(
            R.string.drug_name to "Drug Name",
            R.string.photo_label_requested_ndc to "Requested NDC",
            R.string.photo_label_dispensed_ndc to "Dispensed NDC",
            R.string.ndc to "Ndc",
            R.string.lotNo to "Lot No.",
            R.string.serial_no to "Serial No.",
            R.string.expiry to "Expiry",
            R.string.count to "Count",
            R.string.photo_label_rx_refill to "RxNo-Refill",
            R.string.photo_label_step to "Step",
            R.string.photo_label_date_time to "Date & Time",
            R.string.photo_label_operator to "Operator",
            R.string.location to "Location",
        )
        every { getString(any()) } answers { labels.getValue(firstArg()) }
    }

    private fun solidBitmap(width: Int, height: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bmp.setPixel(x, y, color)
            }
        }
        return bmp
    }

    private fun info(
        drugName: String? = "Test Drug 10mg tablet",
        isStockCount: Boolean = false,
        requestedNdc: String? = "00406-0124-10",
        dispensedNdc: String? = "00406-0124-10",
        lot: String? = "LOT1",
        serial: String? = "SN1",
        expiry: String? = "07-31-2028",
        count: Int = 3,
        rxRefill: String? = "3X-1",
        step: String? = "CONTAINER_INITIATE",
        user: String? = "Test User",
        location: String? = "Mumbai",
        drugImage: Bitmap? = null,
    ) = PhotoInfo(
        drugName = drugName, drugImage = drugImage, isStockCount = isStockCount,
        requestedNdc = requestedNdc, dispensedNdc = dispensedNdc,
        lotNumber = lot, serialNumber = serial, expirationDate = expiry,
        count = count, rxRefill = rxRefill, stepLabel = step,
        userName = user, location = location, timestamp = 1_700_000_000_000L,
    )

    /** First row whose left-edge pixel is [color]: where the photo starts. */
    private fun firstRowOf(bmp: Bitmap, color: Int): Int =
        (0 until bmp.height).first { bmp.getPixel(0, it) == color }

    // ── Strip fields ─────────────────────────────────────────────────────

    @Test
    fun `dispense top row has requested and dispensed NDC`() {
        val labels = OverlayUtils.topRowFields(info(), res).map { it.label }
        assertEquals(listOf("Requested NDC", "Dispensed NDC", "Lot No.", "Serial No.", "Expiry"), labels)
    }

    @Test
    fun `stock count top row has a single NDC`() {
        val fields = OverlayUtils.topRowFields(info(isStockCount = true, requestedNdc = "111"), res)
        assertEquals(listOf("Ndc", "Lot No.", "Serial No.", "Expiry"), fields.map { it.label })
        assertEquals("111", fields.first().value)
    }

    @Test
    fun `missing values show a dash`() {
        val blank = info(lot = null, serial = "", expiry = " ", rxRefill = null, step = null, user = null, location = null)
        val top = OverlayUtils.topRowFields(blank, res).associate { it.label to it.value }
        val bottom = OverlayUtils.bottomRows(blank, res).flatten().associate { it.label to it.value }
        assertEquals("-", top["Lot No."])
        assertEquals("-", top["Serial No."])
        assertEquals("-", top["Expiry"])
        assertEquals("-", bottom["RxNo-Refill"])
        assertEquals("-", bottom["Step"])
        assertEquals("-", bottom["Operator"])
        assertEquals("-", bottom["Location"])
    }

    @Test
    fun `bottom rows follow the screenshot grid`() {
        val rows = OverlayUtils.bottomRows(info(count = 30), res)
        assertEquals(listOf("Count", "RxNo-Refill", "Step", "Date & Time"), rows[0].map { it.label })
        assertEquals(listOf("Operator", "Location"), rows[1].map { it.label })
        assertEquals("30", rows[0][0].value)
        // MM-dd-yyyy HH:mm
        assertTrue(Regex("""\d{2}-\d{2}-\d{4} \d{2}:\d{2}""").matches(rows[0][3].value))
    }

    @Test
    fun `stock count bottom row has no RxNo-Refill column`() {
        val rows = OverlayUtils.bottomRows(info(isStockCount = true), res)
        assertEquals(listOf("Count", "Step", "Date & Time"), rows[0].map { it.label })
    }

    @Test
    fun `rxRefill joins rx and refill`() {
        assertEquals("3X-1", OverlayUtils.rxRefill("3X", "1"))
        assertEquals("3X", OverlayUtils.rxRefill("3X", null))
        assertEquals("3X", OverlayUtils.rxRefill("3X", " "))
        assertNull(OverlayUtils.rxRefill(null, "1"))
    }

    // ── Drawing ──────────────────────────────────────────────────────────

    @Test
    fun `photo sits between two white strips`() {
        val photo = solidBitmap(400, 300, Color.RED)

        val result = OverlayUtils.drawDetectionsOnBitmap(photo, emptyList(), info(), res)

        val top = firstRowOf(result, Color.RED)
        assertEquals(400, result.width)
        assertTrue(top > 0)
        assertTrue(result.height > top + 300)
        assertEquals(Color.WHITE, result.getPixel(399, 0))
        assertEquals(Color.WHITE, result.getPixel(399, result.height - 1))
        assertEquals(Color.RED, result.getPixel(0, top + 299))
        assertTrue(result.getPixel(0, top + 300) != Color.RED)
    }

    @Test
    fun `does not mutate the original bitmap instance`() {
        val photo = solidBitmap(150, 150, Color.BLACK)
        val result = OverlayUtils.drawDetectionsOnBitmap(photo, emptyList(), info(), res)
        assertTrue(result !== photo)
        assertEquals(150, photo.height)
    }

    @Test
    fun `long field value stays on one line so the strip does not grow`() {
        val photo = solidBitmap(400, 300, Color.RED)
        val long = "Andheri East, Mumbai - 400069, ".repeat(6)

        val short = OverlayUtils.drawDetectionsOnBitmap(photo, emptyList(), info(location = "Mumbai"), res)
        val shrunk = OverlayUtils.drawDetectionsOnBitmap(photo, emptyList(), info(location = long), res)

        assertTrue(shrunk.height <= short.height)
    }

    @Test
    fun `long drug name still wraps and makes the strip taller`() {
        val photo = solidBitmap(400, 300, Color.RED)
        val long = "hydrocodone bitartrate/acetaminophen 7.5mg-325mg tablet ".repeat(3)

        val short = OverlayUtils.drawDetectionsOnBitmap(photo, emptyList(), info(drugName = "Aspirin"), res)
        val wrapped = OverlayUtils.drawDetectionsOnBitmap(photo, emptyList(), info(drugName = long), res)

        assertTrue(wrapped.height > short.height)
    }

    @Test
    fun `draws numbered circles on the photo`() {
        val photo = solidBitmap(300, 300, Color.RED)
        val pills = listOf(DetectedPill(x = 0.5f, y = 0.5f, confidence = 0.9f))

        val result = OverlayUtils.drawDetectionsOnBitmap(photo, pills, info(), res)

        val top = firstRowOf(result, Color.RED)
        assertTrue(result.getPixel(150, top + 150) != Color.RED)
    }

    @Test
    fun `zero pills still get both strips and the count badge`() {
        val photo = solidBitmap(1080, 1080, Color.GRAY)

        val result = OverlayUtils.drawDetectionsOnBitmap(photo, emptyList(), info(count = 0), res)

        val top = firstRowOf(result, Color.GRAY)
        assertTrue(top > 0)
        assertTrue(result.height > top + 1080)
        // Badge background, 10 px in from its bottom-right corner (24 px margin, scale 1).
        assertTrue(result.getPixel(1080 - 24 - 10, top + 1080 - 24 - 10) != Color.GRAY)
    }

    @Test
    fun `scaleRefSide keeps the strips the same size on any photo size`() {
        val a = OverlayUtils.drawDetectionsOnBitmap(solidBitmap(600, 300, Color.RED), emptyList(), info(), res, scaleRefSide = 1080)
        val b = OverlayUtils.drawDetectionsOnBitmap(solidBitmap(600, 500, Color.RED), emptyList(), info(), res, scaleRefSide = 1080)
        assertEquals(a.height - 300, b.height - 500)
    }

    @Test
    fun `phone strips are shorter than tablet strips`() {
        // Wide enough that neither row needs shrinking to fit.
        val photo = solidBitmap(2000, 300, Color.RED)
        val tablet = OverlayUtils.drawDetectionsOnBitmap(photo, emptyList(), info(), res, scaleRefSide = 1080)
        val phone = OverlayUtils.drawDetectionsOnBitmap(photo, emptyList(), info(), res, scaleRefSide = 1080, isPhone = true)
        assertTrue(phone.height < tablet.height)
    }

    @Test
    fun `draws the drug image thumbnail`() {
        val photo = solidBitmap(1080, 600, Color.WHITE)
        val thumb = solidBitmap(40, 30, Color.BLUE)

        val result = OverlayUtils.drawDetectionsOnBitmap(photo, emptyList(), info(drugImage = thumb), res, scaleRefSide = 1080)

        // Thumbnail box starts at (24, 24) and is 100 x 70 at scale 1.
        assertEquals(Color.BLUE, result.getPixel(24 + 50, 24 + 35))
    }

    @Test
    fun `handles very small bitmap dimensions without crashing`() {
        val result = OverlayUtils.drawDetectionsOnBitmap(
            solidBitmap(2, 2, Color.BLACK),
            listOf(DetectedPill(x = 0.5f, y = 0.5f, confidence = 0.5f)),
            info(),
            res
        )
        assertEquals(2, result.width)
    }

    // ── savedPhotoCropRegion / pillsInCrop ───────────────────────────────
    // Margin is 0.05 of the tray+chute box per side, min side 64.

    private fun det(l: Float, t: Float, r: Float, b: Float, cls: TrayClass) =
        TrayDetection(rect = RectF(l, t, r, b), confidence = 1f, cls = cls)

    @Test
    fun `crop covers tray and chute grown by the margin`() {
        val trays = listOf(
            det(100f, 100f, 300f, 300f, TrayClass.TRAY),
            det(300f, 150f, 400f, 250f, TrayClass.CHUTE)
        )
        // Union 100,100-400,300 (300x200) → 15 px / 10 px margin.
        assertEquals(Rect(85, 90, 415, 310), OverlayUtils.savedPhotoCropRegion(trays, 640, 480))
    }

    @Test
    fun `crop is clamped to the frame`() {
        val trays = listOf(
            det(0f, 0f, 200f, 200f, TrayClass.TRAY),
            det(200f, 0f, 300f, 100f, TrayClass.CHUTE)
        )
        assertEquals(Rect(0, 0, 280, 210), OverlayUtils.savedPhotoCropRegion(trays, 280, 480))
    }

    @Test
    fun `crop uses the largest tray and the largest chute`() {
        val trays = listOf(
            det(0f, 0f, 10f, 10f, TrayClass.TRAY),
            det(100f, 100f, 300f, 300f, TrayClass.TRAY),
            det(600f, 400f, 605f, 405f, TrayClass.CHUTE),
            det(300f, 150f, 400f, 250f, TrayClass.CHUTE)
        )
        assertEquals(Rect(85, 90, 415, 310), OverlayUtils.savedPhotoCropRegion(trays, 640, 480))
    }

    @Test
    fun `no crop without a chute`() {
        val trays = listOf(det(100f, 100f, 300f, 300f, TrayClass.TRAY))
        assertNull(OverlayUtils.savedPhotoCropRegion(trays, 640, 480))
    }

    @Test
    fun `no crop without a tray`() {
        val trays = listOf(det(300f, 150f, 400f, 250f, TrayClass.CHUTE))
        assertNull(OverlayUtils.savedPhotoCropRegion(trays, 640, 480))
    }

    @Test
    fun `no crop when the region is too small`() {
        val trays = listOf(
            det(0f, 0f, 30f, 30f, TrayClass.TRAY),
            det(30f, 0f, 50f, 30f, TrayClass.CHUTE)
        )
        assertNull(OverlayUtils.savedPhotoCropRegion(trays, 640, 480))
    }

    @Test
    fun `pillsInCrop moves a frame point into crop space`() {
        // Frame 1000x500; pill at (350, 250) px; crop 100,50-600,450 (500x400).
        val moved = OverlayUtils.pillsInCrop(
            listOf(DetectedPill(x = 0.35f, y = 0.5f, confidence = 0.9f)),
            Rect(100, 50, 600, 450), 1000, 500
        ).single()
        assertEquals(0.5f, moved.x, 1e-4f)
        assertEquals(0.5f, moved.y, 1e-4f)
        assertEquals(0.9f, moved.confidence, 0f)
    }
}
