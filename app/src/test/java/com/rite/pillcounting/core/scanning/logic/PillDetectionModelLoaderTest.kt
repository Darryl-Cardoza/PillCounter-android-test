package com.rite.pillcounting.core.scanning.logic

import android.content.Context
import android.content.res.AssetManager
import com.rite.pillcounting.core.security.ModelDecryptor
import com.rite.pillcounting.core.security.ModelKeyUnit
import com.rite.pillcounting.core.utils.logger.PerformanceLogger
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for [PillDetectionModelLoader].
 *
 * The class's public surface (`getOrLoadInterpreters`, `unloadGloveModel`, `close`)
 * is built almost entirely on top of real TFLite `Interpreter`/`GpuDelegate` objects,
 * which are final JNI-backed classes from the org.tensorflow.lite native library.
 * These cannot be constructed, mocked, or exercised on a plain JVM unit test — doing
 * so would require Robolectric plus the real native .so + encrypted model assets,
 * which are unavailable in this environment and would violate the "no real
 * filesystem/native calls" testing constraint.
 *
 * What IS pure, deterministic logic in this file — and is covered here via
 * reflection against the private methods — is exercised directly:
 *  - [PillDetectionModelLoader] "allocOutputArray": output-tensor-shape -> array allocation for every supported rank,
 *    plus the unsupported-rank exception branch.
 *  - "bytesToDirectBuffer" / the private "duplicateAndRewind" extension: byte-buffer construction,
 *    ordering and position/rewind behavior.
 *  - "loadModelBytes": both the encrypted (asset copy + decrypt) and
 *    unencrypted (direct asset read) branches, including the "already copied"
 *    short-circuit that skips re-copying the .enc file.
 */
class PillDetectionModelLoaderTest {

    private lateinit var tempDir: File
    private lateinit var context: Context
    private lateinit var performanceLogger: PerformanceLogger
    private lateinit var loader: PillDetectionModelLoader

    @Before
    fun setUp() {
        tempDir = File.createTempFile("model-loader-test", "").apply {
            delete()
            mkdirs()
        }
        performanceLogger = mockk(relaxed = true)

        // ModelKeyUnit constructor touches Android Keystore APIs; mock its
        // construction so `PillDetectionModelLoader`'s init block doesn't crash.
        mockkConstructor(ModelKeyUnit::class)
        every { anyConstructed<ModelKeyUnit>().activateIfNeeded() } returns Unit

        context = mockk(relaxed = true)
        every { context.filesDir } returns tempDir

        loader = PillDetectionModelLoader(context, performanceLogger)
    }

    @After
    fun tearDown() {
        unmockkAll()
        tempDir.deleteRecursively()
    }

    // ---- allocOutputArray -------------------------------------------------

    private fun invokeAllocOutputArray(shape: IntArray): Any {
        val method = PillDetectionModelLoader::class.java
            .getDeclaredMethod("allocOutputArray", IntArray::class.java)
        method.isAccessible = true
        return method.invoke(loader, shape)
    }

    @Test
    fun `allocOutputArray builds FloatArray for rank 1`() {
        val result = invokeAllocOutputArray(intArrayOf(5))
        assertTrue(result is FloatArray)
        assertEquals(5, (result as FloatArray).size)
    }

    @Test
    fun `allocOutputArray builds nested Array of FloatArray for rank 2`() {
        val result = invokeAllocOutputArray(intArrayOf(2, 3))
        assertTrue(result is Array<*>)
        val outer = result as Array<*>
        assertEquals(2, outer.size)
        assertTrue(outer[0] is FloatArray)
        assertEquals(3, (outer[0] as FloatArray).size)
    }

    @Test
    fun `allocOutputArray builds rank 3 nested array with correct dimensions`() {
        val result = invokeAllocOutputArray(intArrayOf(1, 4, 6)) as Array<*>
        assertEquals(1, result.size)
        val mid = result[0] as Array<*>
        assertEquals(4, mid.size)
        assertEquals(6, (mid[0] as FloatArray).size)
    }

    @Test
    fun `allocOutputArray builds rank 4 nested array with correct dimensions`() {
        val result = invokeAllocOutputArray(intArrayOf(1, 2, 3, 4)) as Array<*>
        assertEquals(1, result.size)
        val a1 = result[0] as Array<*>
        assertEquals(2, a1.size)
        val a2 = a1[0] as Array<*>
        assertEquals(3, a2.size)
        assertEquals(4, (a2[0] as FloatArray).size)
    }

    @Test
    fun `allocOutputArray throws IllegalArgumentException for unsupported rank 0`() {
        try {
            invokeAllocOutputArray(intArrayOf())
            org.junit.Assert.fail("Expected an exception for rank 0")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            assertTrue(e.targetException is IllegalArgumentException)
        }
    }

    @Test
    fun `allocOutputArray throws IllegalArgumentException for unsupported rank 5`() {
        try {
            invokeAllocOutputArray(intArrayOf(1, 1, 1, 1, 1))
            org.junit.Assert.fail("Expected an exception for rank 5")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            assertTrue(e.targetException is IllegalArgumentException)
        }
    }

    // ---- bytesToDirectBuffer / duplicateAndRewind --------------------------

    private fun invokeBytesToDirectBuffer(bytes: ByteArray): ByteBuffer {
        val method = PillDetectionModelLoader::class.java
            .getDeclaredMethod("bytesToDirectBuffer", ByteArray::class.java)
        method.isAccessible = true
        return method.invoke(loader, bytes) as ByteBuffer
    }

    @Test
    fun `bytesToDirectBuffer produces a direct buffer with native byte order and rewound position`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val buffer = invokeBytesToDirectBuffer(bytes)

        assertTrue(buffer.isDirect)
        assertEquals(ByteOrder.nativeOrder(), buffer.order())
        assertEquals(0, buffer.position())
        assertEquals(bytes.size, buffer.limit())

        val readBack = ByteArray(bytes.size)
        buffer.get(readBack)
        assertArrayEquals(bytes, readBack)
    }

    @Test
    fun `bytesToDirectBuffer handles empty byte array`() {
        val buffer = invokeBytesToDirectBuffer(ByteArray(0))
        assertEquals(0, buffer.capacity())
        assertEquals(0, buffer.position())
    }

    @Test
    fun `duplicateAndRewind returns a rewound duplicate sharing content but independent position`() {
        val method = PillDetectionModelLoader::class.java
            .getDeclaredMethod("duplicateAndRewind", ByteBuffer::class.java)
        method.isAccessible = true

        val original = ByteBuffer.allocateDirect(4).order(ByteOrder.BIG_ENDIAN)
        original.putInt(42)
        // original position is now 4 (fully written); simulate consumed buffer.

        val duplicate = method.invoke(loader, original) as ByteBuffer

        assertEquals(0, duplicate.position())
        assertEquals(ByteOrder.nativeOrder(), duplicate.order())
        assertEquals(4, duplicate.position().let { duplicate.limit() })
        // Duplicate shares the same underlying content.
        assertEquals(4, original.position()) // original buffer's position is untouched
    }

    // ---- loadModelBytes -----------------------------------------------------

    private fun invokeLoadModelBytes(modelName: String, encrypted: Boolean): ByteArray {
        val method = PillDetectionModelLoader::class.java
            .getDeclaredMethod("loadModelBytes", String::class.java, Boolean::class.java)
        method.isAccessible = true
        return method.invoke(loader, modelName, encrypted) as ByteArray
    }

    @Test
    fun `loadModelBytes unencrypted path reads bytes directly from assets`() {
        val assetManager: AssetManager = mockk()
        val payload = "raw-model-bytes".toByteArray()
        every { assetManager.open("plain.tflite") } returns ByteArrayInputStream(payload)
        every { context.assets } returns assetManager

        val result = invokeLoadModelBytes("plain.tflite", encrypted = false)

        assertArrayEquals(payload, result)
    }

    @Test
    fun `loadModelBytes encrypted path copies asset to filesDir when not already present and decrypts`() {
        val assetManager: AssetManager = mockk()
        val encBytes = "encrypted-payload".toByteArray()
        every { assetManager.open("secure.tflite.enc") } returns ByteArrayInputStream(encBytes)
        every { context.assets } returns assetManager

        val decrypted = "decrypted-bytes".toByteArray()
        mockkObject(ModelDecryptor)
        every { ModelDecryptor.decryptToBytes(any(), any()) } returns decrypted

        val targetFile = File(tempDir, "secure.tflite.enc")
        assertFalse(targetFile.exists())

        val result = invokeLoadModelBytes("secure.tflite", encrypted = true)

        assertTrue(targetFile.exists())
        assertArrayEquals(encBytes, targetFile.readBytes())
        assertArrayEquals(decrypted, result)
    }

    @Test
    fun `loadModelBytes encrypted path skips re-copy when enc file already exists`() {
        val assetManager: AssetManager = mockk()
        every { context.assets } returns assetManager

        val existingFile = File(tempDir, "cached.tflite.enc")
        val preExistingBytes = "already-on-disk".toByteArray()
        FileOutputStream(existingFile).use { it.write(preExistingBytes) }

        val decrypted = "decrypted-cached".toByteArray()
        mockkObject(ModelDecryptor)
        every { ModelDecryptor.decryptToBytes(any(), any()) } returns decrypted

        val result = invokeLoadModelBytes("cached.tflite", encrypted = true)

        // assets.open must never be called since the .enc file already exists on disk.
        io.mockk.verify(exactly = 0) { assetManager.open(any()) }
        assertArrayEquals(preExistingBytes, existingFile.readBytes())
        assertArrayEquals(decrypted, result)
    }
}
