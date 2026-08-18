package com.rite.pillcounting.core.faceAuth.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric for a real RectF: the plain-JUnit android.jar stubs (returnDefaultValues)
// no-op its constructor, leaving every box 0,0,0,0.
@RunWith(RobolectricTestRunner::class)
class YuNetDecoderTest {

    private val strides = intArrayOf(8, 16, 32)

    /** Builds one stride's 4 raw outputs: bbox[anchors,4], kps[anchors,10], scoreA[anchors,1], scoreB[anchors,1]. */
    private fun stubStride(inputW: Int, inputH: Int, stride: Int, hitCol: Int, hitRow: Int, cls: Float, obj: Float): List<RawOutput> {
        val cols = inputW / stride
        val rows = inputH / stride
        val n = cols * rows
        val hitIdx = hitRow * cols + hitCol

        val bbox = FloatArray(n * 4)
        // tx, ty, tw(log), th(log) — all zero offset/scale except at the hit cell.
        bbox[hitIdx * 4 + 2] = 0f // exp(0) = 1 -> width = 1*stride
        bbox[hitIdx * 4 + 3] = 0f

        val kps = FloatArray(n * 10) // all landmarks land at the cell's own grid position

        val scoreA = FloatArray(n)
        val scoreB = FloatArray(n)
        scoreA[hitIdx] = cls
        scoreB[hitIdx] = obj

        return listOf(
            RawOutput(intArrayOf(1, n, 4), bbox),
            RawOutput(intArrayOf(1, n, 10), kps),
            RawOutput(intArrayOf(1, n, 1), scoreA),
            RawOutput(intArrayOf(1, n, 1), scoreB),
        )
    }

    @Test
    fun `decode finds a single strong detection at stride 8`() {
        val inputW = 640
        val inputH = 640
        val raw = stubStride(inputW, inputH, stride = 8, hitCol = 10, hitRow = 5, cls = 0.95f, obj = 0.95f) +
            stubStride(inputW, inputH, stride = 16, hitCol = 0, hitRow = 0, cls = 0f, obj = 0f) +
            stubStride(inputW, inputH, stride = 32, hitCol = 0, hitRow = 0, cls = 0f, obj = 0f)

        val faces = YuNetDecoder.decode(raw, inputW, inputH, scoreThreshold = 0.85f, nmsThreshold = 0.3f)

        assertEquals(1, faces.size)
        assertTrue(faces[0].score >= 0.85f)
        // cx = (col + 0)*stride = 10*8 = 80; box x = cx - bw/2 = 80 - 4 = 76
        assertEquals(76f, faces[0].rect.left, 0.5f)
    }

    @Test
    fun `decode returns empty list when nothing clears the score threshold`() {
        val inputW = 640
        val inputH = 640
        val raw = stubStride(inputW, inputH, stride = 8, hitCol = 0, hitRow = 0, cls = 0.1f, obj = 0.1f) +
            stubStride(inputW, inputH, stride = 16, hitCol = 0, hitRow = 0, cls = 0f, obj = 0f) +
            stubStride(inputW, inputH, stride = 32, hitCol = 0, hitRow = 0, cls = 0f, obj = 0f)

        val faces = YuNetDecoder.decode(raw, inputW, inputH, scoreThreshold = 0.85f, nmsThreshold = 0.3f)

        assertTrue(faces.isEmpty())
    }
}
