package com.rite.pillcounting.core.scanning.logic

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * Unit tests for [Postprocessor].
 *
 * [Postprocessor.allocateOutputs] talks to a real/mocked [Interpreter] to discover
 * tensor shapes and allocate matching direct ByteBuffers; it is exercised here with
 * a mocked [Interpreter] returning mocked [Tensor]s of controlled shapes.
 *
 * [Postprocessor.decode] and the private `dflProject` DFL-softmax math are pure
 * float arithmetic over [Postprocessor.PillOutputs] buffers we populate ourselves,
 * so they are fully exercised without any Android/TFLite runtime.
 *
 * Run under Robolectric: [Postprocessor.decode] returns `Detection(rect = RectF(...))`,
 * and reading `det.rect.left/top/right/bottom` back on the plain JVM (this module's
 * isReturnDefaultValues=true test option) yields 0f for every field, breaking every
 * assertion that checks decoded box coordinates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PostprocessorTest {

    private val fpnGrids = intArrayOf(80, 40, 20)
    private val fpnStrides = intArrayOf(8, 16, 32)
    private val dflBins = 17
    private val regChannels = 4 * dflBins // 68

    // ---------------------------------------------------------------------
    // allocateOutputs
    // ---------------------------------------------------------------------

    private fun mockTensor(shape: IntArray): Tensor {
        val t = mockk<Tensor>()
        every { t.shape() } returns shape
        return t
    }

    /** Builds an Interpreter mock whose output tensors match the real 6-tensor model. */
    private fun mockInterpreter(): Interpreter {
        val interpreter = mockk<Interpreter>()
        // Order shuffled to mimic TFLite's auto-generated PartitionedCall:N reordering.
        val tensors = listOf(
            mockTensor(intArrayOf(1, 40, 40, 1)),           // cls stride16
            mockTensor(intArrayOf(1, 80, 80, regChannels)), // reg stride8
            mockTensor(intArrayOf(1, 20, 20, 1)),           // cls stride32
            mockTensor(intArrayOf(1, 80, 80, 1)),           // cls stride8
            mockTensor(intArrayOf(1, 40, 40, regChannels)), // reg stride16
            mockTensor(intArrayOf(1, 20, 20, regChannels))  // reg stride32
        )
        every { interpreter.outputTensorCount } returns tensors.size
        every { interpreter.getOutputTensor(any()) } answers { tensors[firstArg<Int>()] }
        return interpreter
    }

    @Test
    fun `allocateOutputs matches cls and reg buffers to correct FPN levels by shape`() {
        val interpreter = mockInterpreter()

        val outputs = Postprocessor.allocateOutputs(interpreter)

        // clsIdx/regIdx must all be resolved (no -1 left).
        assertTrue(outputs.clsIdx.none { it < 0 })
        assertTrue(outputs.regIdx.none { it < 0 })

        for (level in fpnGrids.indices) {
            val grid = fpnGrids[level]
            assertEquals(grid * grid, outputs.clsFlat[level].size)
            assertEquals(grid * grid * regChannels, outputs.regFlat[level].size)
            assertEquals(grid * grid * 4, outputs.clsBuffers[level].capacity())
            assertEquals(grid * grid * regChannels * 4, outputs.regBuffers[level].capacity())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `allocateOutputs throws when a required output tensor is missing`() {
        val interpreter = mockk<Interpreter>()
        // Only 5 of the 6 expected tensors present -> stride32 reg tensor missing.
        val tensors = listOf(
            mockTensor(intArrayOf(1, 80, 80, 1)),
            mockTensor(intArrayOf(1, 80, 80, regChannels)),
            mockTensor(intArrayOf(1, 40, 40, 1)),
            mockTensor(intArrayOf(1, 40, 40, regChannels)),
            mockTensor(intArrayOf(1, 20, 20, 1))
            // stride32 reg tensor deliberately omitted
        )
        every { interpreter.outputTensorCount } returns tensors.size
        every { interpreter.getOutputTensor(any()) } answers { tensors[firstArg<Int>()] }

        Postprocessor.allocateOutputs(interpreter)
    }

    @Test
    fun `allocateOutputs ignores tensors with unexpected shape or channel count`() {
        val interpreter = mockk<Interpreter>()
        val tensors = listOf(
            mockTensor(intArrayOf(1, 80, 80, 1)),
            mockTensor(intArrayOf(1, 80, 80, regChannels)),
            mockTensor(intArrayOf(1, 40, 40, 1)),
            mockTensor(intArrayOf(1, 40, 40, regChannels)),
            mockTensor(intArrayOf(1, 20, 20, 1)),
            mockTensor(intArrayOf(1, 20, 20, regChannels)),
            // Junk tensors that must be skipped, not crash allocation:
            mockTensor(intArrayOf(1, 80, 40, 1)),      // non-square -> shape[1] != shape[2]
            mockTensor(intArrayOf(80, 80, 1)),         // wrong rank
            mockTensor(intArrayOf(1, 99, 99, 1)),      // grid not in FPN_GRIDS
            mockTensor(intArrayOf(1, 80, 80, 5))       // channel count not 1 or 68
        )
        every { interpreter.outputTensorCount } returns tensors.size
        every { interpreter.getOutputTensor(any()) } answers { tensors[firstArg<Int>()] }

        val outputs = Postprocessor.allocateOutputs(interpreter)

        assertTrue(outputs.clsIdx.none { it < 0 })
        assertTrue(outputs.regIdx.none { it < 0 })
    }

    // ---------------------------------------------------------------------
    // decode / dflProject
    // ---------------------------------------------------------------------

    /** Builds a PillOutputs with all-zero buffers at every FPN level. */
    private fun emptyOutputs(): Postprocessor.PillOutputs {
        val clsBuffers = Array(3) { level ->
            val grid = fpnGrids[level]
            ByteBuffer.allocateDirect(grid * grid * 4).order(ByteOrder.nativeOrder())
        }
        val regBuffers = Array(3) { level ->
            val grid = fpnGrids[level]
            ByteBuffer.allocateDirect(grid * grid * regChannels * 4).order(ByteOrder.nativeOrder())
        }
        val clsFlat = Array(3) { level -> FloatArray(fpnGrids[level] * fpnGrids[level]) }
        val regFlat = Array(3) { level -> FloatArray(fpnGrids[level] * fpnGrids[level] * regChannels) }
        return Postprocessor.PillOutputs(
            clsBuffers = clsBuffers,
            regBuffers = regBuffers,
            clsFlat = clsFlat,
            regFlat = regFlat,
            clsIdx = intArrayOf(0, 1, 2),
            regIdx = intArrayOf(0, 1, 2)
        )
    }

    /** Sets the score at (row, col) in the given level's cls buffer. */
    private fun setScore(outputs: Postprocessor.PillOutputs, level: Int, row: Int, col: Int, score: Float) {
        val grid = fpnGrids[level]
        val fb = outputs.clsBuffers[level].asFloatBuffer()
        fb.put(row * grid + col, score)
    }

    /**
     * Sets the four DFL 17-bin logit slices at (row, col) so that after softmax+dot
     * each side decodes to exactly [left], [top], [right], [bottom] distance-in-bins
     * (0..16). We do this by putting all probability mass on a single bin index.
     */
    private fun setDistBins(
        outputs: Postprocessor.PillOutputs,
        level: Int,
        row: Int,
        col: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        val grid = fpnGrids[level]
        val cellIdx = row * grid + col
        val base = cellIdx * regChannels
        val fb = outputs.regBuffers[level].asFloatBuffer()
        val sides = intArrayOf(left, top, right, bottom)
        for (side in 0 until 4) {
            val sideBase = base + side * dflBins
            for (bin in 0 until dflBins) {
                // Large logit on the target bin, ~-inf (very negative) elsewhere so
                // softmax collapses onto that bin => dot product == bin index.
                fb.put(sideBase + bin, if (bin == sides[side]) 20f else -20f)
            }
        }
    }

    @Test
    fun `decode returns empty list when all scores below threshold`() {
        val outputs = emptyOutputs()
        // All scores are 0f by default (below any positive threshold).
        val result = Postprocessor.decode(outputs, confThreshold = 0.25f, scale = 1f, padX = 0f, padY = 0f)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `decode produces detection with exact box coordinates for a single anchor`() {
        val outputs = emptyOutputs()
        val level = 0 // stride 8, grid 80
        val row = 10
        val col = 20
        setScore(outputs, level, row, col, 0.9f)
        // distances (in stride units) = 2 left, 3 top, 4 right, 5 bottom
        setDistBins(outputs, level, row, col, left = 2, top = 3, right = 4, bottom = 5)

        val result = Postprocessor.decode(outputs, confThreshold = 0.25f, scale = 1f, padX = 0f, padY = 0f)

        assertEquals(1, result.size)
        val det = result[0]
        assertEquals(0.9f, det.confidence, 1e-6f)

        val stride = 8f
        val cx = (col + 0.5f) * stride
        val cy = (row + 0.5f) * stride
        val expectedX1 = cx - 2 * stride
        val expectedY1 = cy - 3 * stride
        val expectedX2 = cx + 4 * stride
        val expectedY2 = cy + 5 * stride

        assertEquals(expectedX1, det.rect.left, 0.05f)
        assertEquals(expectedY1, det.rect.top, 0.05f)
        assertEquals(expectedX2, det.rect.right, 0.05f)
        assertEquals(expectedY2, det.rect.bottom, 0.05f)
    }

    @Test
    fun `decode applies letterbox unscale and padding reversal`() {
        val outputs = emptyOutputs()
        val level = 0
        val row = 0
        val col = 0
        setScore(outputs, level, row, col, 1f)
        setDistBins(outputs, level, row, col, left = 1, top = 1, right = 1, bottom = 1)

        val scale = 2f
        val padX = 10f
        val padY = 5f
        val result = Postprocessor.decode(outputs, confThreshold = 0.5f, scale = scale, padX = padX, padY = padY)

        assertEquals(1, result.size)
        val stride = 8f
        val cx = 0.5f * stride
        val cy = 0.5f * stride
        val expectedX1 = ((cx - stride) - padX) / scale
        val expectedY1 = ((cy - stride) - padY) / scale
        assertEquals(expectedX1, result[0].rect.left, 0.05f)
        assertEquals(expectedY1, result[0].rect.top, 0.05f)
    }

    @Test
    fun `decode includes anchor exactly at confidence threshold boundary`() {
        val outputs = emptyOutputs()
        setScore(outputs, 0, 0, 0, 0.25f)
        setDistBins(outputs, 0, 0, 0, 0, 0, 0, 0)

        val result = Postprocessor.decode(outputs, confThreshold = 0.25f, scale = 1f, padX = 0f, padY = 0f)

        assertEquals(1, result.size)
    }

    @Test
    fun `decode excludes anchor just below confidence threshold`() {
        val outputs = emptyOutputs()
        setScore(outputs, 0, 0, 0, 0.2499999f)
        setDistBins(outputs, 0, 0, 0, 0, 0, 0, 0)

        val result = Postprocessor.decode(outputs, confThreshold = 0.25f, scale = 1f, padX = 0f, padY = 0f)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `decode collects detections across all three FPN levels`() {
        val outputs = emptyOutputs()
        setScore(outputs, 0, 1, 1, 0.5f)
        setDistBins(outputs, 0, 1, 1, 0, 0, 0, 0)
        setScore(outputs, 1, 2, 2, 0.6f)
        setDistBins(outputs, 1, 2, 2, 0, 0, 0, 0)
        setScore(outputs, 2, 3, 3, 0.7f)
        setDistBins(outputs, 2, 3, 3, 0, 0, 0, 0)

        val result = Postprocessor.decode(outputs, confThreshold = 0.25f, scale = 1f, padX = 0f, padY = 0f)

        assertEquals(3, result.size)
        val confidences = result.map { it.confidence }.sorted()
        assertEquals(listOf(0.5f, 0.6f, 0.7f), confidences)
    }

    @Test
    fun `decode handles multiple qualifying anchors within a single level`() {
        val outputs = emptyOutputs()
        setScore(outputs, 0, 5, 5, 0.3f)
        setDistBins(outputs, 0, 5, 5, 1, 1, 1, 1)
        setScore(outputs, 0, 6, 6, 0.4f)
        setDistBins(outputs, 0, 6, 6, 2, 2, 2, 2)

        val result = Postprocessor.decode(outputs, confThreshold = 0.25f, scale = 1f, padX = 0f, padY = 0f)

        assertEquals(2, result.size)
    }

    @Test
    fun `dflProject via decode produces expected weighted average for uniform logits`() {
        // Uniform logits across all 17 bins -> softmax is uniform -> dot with [0..16]
        // = mean of 0..16 = 8.0 exactly (verifies numerically-stable softmax math).
        val outputs = emptyOutputs()
        setScore(outputs, 0, 0, 0, 1f)
        val grid = fpnGrids[0]
        val cellIdx = 0
        val base = cellIdx * regChannels
        val fb = outputs.regBuffers[0].asFloatBuffer()
        for (i in 0 until regChannels) {
            fb.put(base + i, 3f) // uniform logits -> uniform softmax
        }

        val result = Postprocessor.decode(outputs, confThreshold = 0.5f, scale = 1f, padX = 0f, padY = 0f)

        assertEquals(1, result.size)
        val stride = 8f
        val cx = 0.5f * stride
        val cy = 0.5f * stride
        val expectedDist = 8.0f * stride // mean bin index 8.0 * stride
        assertEquals(cx - expectedDist, result[0].rect.left, 0.05f)
        assertEquals(cy - expectedDist, result[0].rect.top, 0.05f)
        assertEquals(cx + expectedDist, result[0].rect.right, 0.05f)
        assertEquals(cy + expectedDist, result[0].rect.bottom, 0.05f)
    }

    @Test
    fun `decode with confThreshold above 1 yields no detections regardless of scores`() {
        val outputs = emptyOutputs()
        setScore(outputs, 0, 0, 0, 1f)
        setDistBins(outputs, 0, 0, 0, 0, 0, 0, 0)

        val result = Postprocessor.decode(outputs, confThreshold = 1.5f, scale = 1f, padX = 0f, padY = 0f)

        assertTrue(result.isEmpty())
    }
}
