package com.rite.pillcounting.core.faceAuth.logic

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class FaceAlignerTest {

    @Test
    fun `identity mapping when src equals dst`() {
        val pts = arrayOf(
            floatArrayOf(38.2946f, 51.6963f),
            floatArrayOf(73.5318f, 51.5014f),
            floatArrayOf(56.0252f, 71.7366f),
            floatArrayOf(41.5493f, 92.3655f),
            floatArrayOf(70.7299f, 92.2041f),
        )
        val m = FaceAligner.similarityTransform(pts, pts)
        // 2x3 identity-ish: [1,0,0, 0,1,0]
        assertEquals(1f, m[0], 1e-3f)
        assertEquals(0f, m[1], 1e-3f)
        assertEquals(0f, m[2], 1e-3f)
        assertEquals(0f, m[3], 1e-3f)
        assertEquals(1f, m[4], 1e-3f)
        assertEquals(0f, m[5], 1e-3f)
    }

    @Test
    fun `pure translation maps every source point onto its shifted destination`() {
        val src = arrayOf(
            floatArrayOf(0f, 0f), floatArrayOf(10f, 0f), floatArrayOf(10f, 10f),
            floatArrayOf(0f, 10f), floatArrayOf(5f, 5f)
        )
        val dst = src.map { floatArrayOf(it[0] + 20f, it[1] + 30f) }.toTypedArray()

        val m = FaceAligner.similarityTransform(src, dst)

        for (i in src.indices) {
            val x = m[0] * src[i][0] + m[1] * src[i][1] + m[2]
            val y = m[3] * src[i][0] + m[4] * src[i][1] + m[5]
            assertEquals(dst[i][0], x, 1e-2f)
            assertEquals(dst[i][1], y, 1e-2f)
        }
    }

    @Test
    fun `uniform scale maps every source point onto its scaled destination`() {
        val src = arrayOf(
            floatArrayOf(0f, 0f), floatArrayOf(10f, 0f), floatArrayOf(10f, 10f),
            floatArrayOf(0f, 10f), floatArrayOf(5f, 5f)
        )
        val dst = src.map { floatArrayOf(it[0] * 2f, it[1] * 2f) }.toTypedArray()

        val m = FaceAligner.similarityTransform(src, dst)

        for (i in src.indices) {
            val x = m[0] * src[i][0] + m[1] * src[i][1] + m[2]
            val y = m[3] * src[i][0] + m[4] * src[i][1] + m[5]
            assertEquals(dst[i][0], x, abs(1e-2f))
            assertEquals(dst[i][1], y, abs(1e-2f))
        }
    }
}
