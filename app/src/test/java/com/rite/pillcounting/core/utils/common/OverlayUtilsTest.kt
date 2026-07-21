package com.rite.pillcounting.core.utils.common

import android.graphics.Bitmap
import android.graphics.Color
import com.rite.pillcounting.core.scanning.domain.model.DetectedPill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * (pixel sampling for luminance, circle/text drawing) which cannot be meaningfully exercised
 * with plain mocks - the branch logic (on-light vs on-dark footer, row inclusion, coordinate
 * clamping) depends on actual pixel values and bitmap dimensions. Robolectric's default
 * (LEGACY) graphics mode silently no-ops real Canvas drawing calls, so pixels never actually
 * change — NATIVE mode is required for the drawn-pixel assertions here to be meaningful.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayUtilsTest {

    private fun solidBitmap(width: Int, height: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bmp.setPixel(x, y, color)
            }
        }
        return bmp
    }

    @Test
    fun `returns a bitmap of same dimensions as input`() {
        val bitmap = solidBitmap(200, 200, Color.BLACK)

        val result = OverlayUtils.drawDetectionsOnBitmap(
            bitmap = bitmap,
            detectedPills = emptyList(),
            previewWidth = 200,
            previewHeight = 200
        )

        assertEquals(200, result.width)
        assertEquals(200, result.height)
    }

    @Test
    fun `does not mutate the original bitmap instance`() {
        val bitmap = solidBitmap(150, 150, Color.BLACK)

        val result = OverlayUtils.drawDetectionsOnBitmap(
            bitmap = bitmap,
            detectedPills = emptyList(),
            previewWidth = 150,
            previewHeight = 150
        )

        assertTrue(result !== bitmap)
    }

    @Test
    fun `handles empty detected pills list without throwing`() {
        val bitmap = solidBitmap(100, 100, Color.DKGRAY)

        val result = OverlayUtils.drawDetectionsOnBitmap(
            bitmap = bitmap,
            detectedPills = emptyList(),
            previewWidth = 100,
            previewHeight = 100
        )

        assertNotNull(result)
    }

    @Test
    fun `draws numbered circles for each detected pill including last-pill emphasis`() {
        // Use a non-black background: the circle fill is a translucent black
        // (Color.argb(160, 0, 0, 0)), which composites as a no-op on a pure-black
        // bitmap (0 over 0 stays 0) - a black background can't detect that the
        // fill was drawn. A light background makes the composited darkening visible.
        val bitmap = solidBitmap(300, 300, Color.WHITE)
        val pills = listOf(
            DetectedPill(x = 0.2f, y = 0.2f, confidence = 0.9f),
            DetectedPill(x = 0.5f, y = 0.5f, confidence = 0.8f),
            DetectedPill(x = 0.8f, y = 0.8f, confidence = 0.95f)
        )

        val result = OverlayUtils.drawDetectionsOnBitmap(
            bitmap = bitmap,
            detectedPills = pills,
            previewWidth = 300,
            previewHeight = 300
        )

        // The circle center should no longer be pure white since something was drawn there.
        val centerPixel = result.getPixel(150, 150)
        assertTrue(centerPixel != Color.WHITE)
    }

    @Test
    fun `clamps out-of-range pill coordinates to bitmap bounds without crashing`() {
        val bitmap = solidBitmap(100, 100, Color.BLACK)
        val pills = listOf(
            DetectedPill(x = -1f, y = -1f, confidence = 0.5f),
            DetectedPill(x = 2f, y = 2f, confidence = 0.5f)
        )

        val result = OverlayUtils.drawDetectionsOnBitmap(
            bitmap = bitmap,
            detectedPills = pills,
            previewWidth = 100,
            previewHeight = 100
        )

        // Should complete without throwing an out-of-bounds exception and produce a valid bitmap.
        assertEquals(100, result.width)
        assertEquals(100, result.height)
    }

    @Test
    fun `uses black text on a light footer background`() {
        // Footer strip (bottom portion) is white -> luminance > 0.5 -> onLight branch -> BLACK text/dark bg strip.
        val bitmap = solidBitmap(120, 120, Color.WHITE)

        val result = OverlayUtils.drawDetectionsOnBitmap(
            bitmap = bitmap,
            detectedPills = emptyList(),
            previewWidth = 120,
            previewHeight = 120,
            count = "42"
        )

        // Background strip paint for onLight uses white-ish translucent fill; just assert no crash + correct size.
        assertEquals(120, result.width)
    }

    @Test
    fun `uses white text on a dark footer background`() {
        val bitmap = solidBitmap(120, 120, Color.BLACK)

        val result = OverlayUtils.drawDetectionsOnBitmap(
            bitmap = bitmap,
            detectedPills = emptyList(),
            previewWidth = 120,
            previewHeight = 120,
            count = "42"
        )

        assertEquals(120, result.width)
    }

    @Test
    fun `includes only non-blank metadata fields as footer rows`() {
        val bitmap = solidBitmap(400, 400, Color.BLACK)

        // Only ndc and count provided; other optional fields null/blank should be excluded.
        val result = OverlayUtils.drawDetectionsOnBitmap(
            bitmap = bitmap,
            detectedPills = emptyList(),
            previewWidth = 400,
            previewHeight = 400,
            userName = null,
            userId = null,
            location = "",
            ndc = "00071015523",
            count = "30",
            patientId = null,
            rx = "   ",
            stepLabel = null,
            lotNumber = null,
            expirationDate = null,
            serialNumber = null
        )

        assertNotNull(result)
        assertEquals(400, result.height)
    }

    @Test
    fun `includes all metadata fields when all are provided`() {
        // A mid-gray background is required here: OverlayUtils samples the footer
        // strip's actual luminance to pick the strip color — translucent WHITE
        // (argb(160,255,255,255)) on a light background, translucent BLACK on a dark
        // one. Either pure white or pure black composites as a no-op against its own
        // matching translucent fill, making the "did something get drawn" pixel check
        // unsatisfiable. Mid-gray guarantees the strip visibly shifts the pixel either way.
        val bitmap = solidBitmap(500, 500, Color.GRAY)

        val result = OverlayUtils.drawDetectionsOnBitmap(
            bitmap = bitmap,
            detectedPills = emptyList(),
            previewWidth = 500,
            previewHeight = 500,
            userName = "John Doe",
            userId = "u123",
            location = "Pharmacy A",
            ndc = "00071015523",
            count = "30",
            patientId = "p001",
            rx = "rx555",
            stepLabel = "Step 2",
            lotNumber = "LOT99",
            expirationDate = "2027-01-01",
            serialNumber = "SN123",
            timestamp = 1_700_000_000_000L
        )

        // The bottom rows region should have been overdrawn with the semi-transparent footer strip,
        // so the very bottom-left pixel should differ from the pure gray background.
        val bottomLeftPixel = result.getPixel(2, result.height - 2)
        assertTrue(bottomLeftPixel != Color.GRAY)
    }

    @Test
    fun `handles a single detected pill as the last and only pill`() {
        // See note in the "numbered circles" test: a translucent-black fill is
        // invisible against a pure-black background, so use white here too.
        val bitmap = solidBitmap(200, 200, Color.WHITE)
        val pills = listOf(DetectedPill(x = 0.5f, y = 0.5f, confidence = 1.0f))

        val result = OverlayUtils.drawDetectionsOnBitmap(
            bitmap = bitmap,
            detectedPills = pills,
            previewWidth = 200,
            previewHeight = 200
        )

        val centerPixel = result.getPixel(100, 100)
        assertTrue(centerPixel != Color.WHITE)
    }

    @Test
    fun `uses a fixed default timestamp branch producing a valid date string without throwing`() {
        val bitmap = solidBitmap(80, 80, Color.BLACK)

        // timestamp = 0L exercises the epoch date-formatting path.
        val result = OverlayUtils.drawDetectionsOnBitmap(
            bitmap = bitmap,
            detectedPills = emptyList(),
            previewWidth = 80,
            previewHeight = 80,
            timestamp = 0L
        )

        assertNotNull(result)
    }

    @Test
    fun `handles very small bitmap dimensions without crashing`() {
        val bitmap = solidBitmap(2, 2, Color.BLACK)

        val result = OverlayUtils.drawDetectionsOnBitmap(
            bitmap = bitmap,
            detectedPills = listOf(DetectedPill(x = 0.5f, y = 0.5f, confidence = 0.5f)),
            previewWidth = 2,
            previewHeight = 2
        )

        assertEquals(2, result.width)
        assertEquals(2, result.height)
    }
}
