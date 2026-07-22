package com.rite.pillcounting.core.hl7.imageWebService

import android.content.Context
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class PrivateTempFileManagerTest {

    private lateinit var context: Context
    private lateinit var cacheDir: File
    private lateinit var manager: PrivateTempFileManager

    @Before
    fun setUp() {
        cacheDir = File.createTempFile("cache_root_", "").apply {
            delete()
            mkdirs()
        }
        context = mockk(relaxed = true)
        io.mockk.every { context.cacheDir } returns cacheDir
        manager = PrivateTempFileManager(context)
    }

    @After
    fun tearDown() {
        cacheDir.deleteRecursively()
    }

    @Test
    fun `createTempFile creates a real file inside context cacheDir`() {
        val tempFile = manager.createTempFile("hint.txt")

        val createdFile = File(tempFile.name)
        assertTrue(createdFile.exists())
        assertEquals(cacheDir.absolutePath, createdFile.parentFile?.absolutePath)
    }

    @Test
    fun `createTempFile ignores filename hint and uses nano_ prefix`() {
        val tempFile = manager.createTempFile("some_custom_hint")

        val createdFile = File(tempFile.name)
        assertTrue(createdFile.name.startsWith("nano_"))
    }

    @Test
    fun `createTempFile with null hint still creates a valid file`() {
        val tempFile = manager.createTempFile(null)

        val createdFile = File(tempFile.name)
        assertTrue(createdFile.exists())
    }

    @Test
    fun `createTempFile with empty hint still creates a valid file`() {
        val tempFile = manager.createTempFile("")

        val createdFile = File(tempFile.name)
        assertTrue(createdFile.exists())
    }

    @Test
    fun `open returns a FileOutputStream that can write bytes to the underlying file`() {
        val tempFile = manager.createTempFile("hint")

        tempFile.open().use { it.write("hello".toByteArray()) }

        val createdFile = File(tempFile.name)
        assertEquals("hello", createdFile.readText())
    }

    @Test
    fun `delete removes the underlying file`() {
        val tempFile = manager.createTempFile("hint")
        val createdFile = File(tempFile.name)
        assertTrue(createdFile.exists())

        tempFile.delete()

        assertFalse(createdFile.exists())
    }

    @Test
    fun `delete is safe to call twice and does not throw`() {
        val tempFile = manager.createTempFile("hint")

        tempFile.delete()
        tempFile.delete()

        assertFalse(File(tempFile.name).exists())
    }

    @Test
    fun `delete on already externally-removed file does not throw`() {
        val tempFile = manager.createTempFile("hint")
        val createdFile = File(tempFile.name)
        createdFile.delete()

        tempFile.delete()

        assertFalse(createdFile.exists())
    }

    @Test
    fun `clear deletes all tracked temp files and empties tracking list`() {
        val first = manager.createTempFile("a")
        val second = manager.createTempFile("b")
        val firstFile = File(first.name)
        val secondFile = File(second.name)
        assertTrue(firstFile.exists())
        assertTrue(secondFile.exists())

        manager.clear()

        assertFalse(firstFile.exists())
        assertFalse(secondFile.exists())
    }

    @Test
    fun `clear with no created temp files does nothing and does not throw`() {
        manager.clear()
        // No exception means success; nothing was tracked so nothing to assert on disk.
        assertTrue(true)
    }

    @Test
    fun `clear after files already externally deleted does not throw`() {
        val tempFile = manager.createTempFile("a")
        File(tempFile.name).delete()

        manager.clear()

        assertFalse(File(tempFile.name).exists())
    }

    @Test
    fun `calling clear twice in a row is safe`() {
        manager.createTempFile("a")

        manager.clear()
        manager.clear()

        assertTrue(true)
    }

    @Test
    fun `multiple createTempFile calls produce distinct file paths`() {
        val first = manager.createTempFile("a")
        val second = manager.createTempFile("b")

        assertTrue(first.name != second.name)
    }
}
