package com.dispensesure.retail.core.utils.logger.destination

import android.content.Context
import android.util.Log
import com.dispensesure.retail.core.utils.logger.LogEntry
import com.dispensesure.retail.core.utils.logger.LogLevel
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit tests for [FileLogDestination]'s file creation and append behaviour. `context.filesDir`
 * is pointed at a real temp directory so writes exercise real file I/O, following the same
 * approach as `PerformanceLoggerTest`.
 */
class FileLogDestinationTest {

    private lateinit var tempDir: File
    private lateinit var context: Context

    @Before
    fun setup() {
        tempDir = kotlin.io.path.createTempDirectory(prefix = "file_log_destination_test_").toFile()

        mockkStatic(Log::class)
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        context = mockk(relaxed = true)
        every { context.applicationContext } returns context
        every { context.filesDir } returns tempDir
    }

    @After
    fun tearDown() {
        unmockkAll()
        tempDir.deleteRecursively()
    }

    private fun entry(message: String = "a message", throwable: Throwable? = null) = LogEntry(
        timestampMillis = 0L,
        level = LogLevel.ERROR,
        fileName = "Foo.kt",
        className = "Foo",
        methodName = "bar",
        message = message,
        throwable = throwable
    )

    @Test
    fun `write creates logs directory and dispensesure_logs-txt file under app-private files dir`() {
        val destination = FileLogDestination(context)

        destination.write(entry())
        destination.awaitIdleForTest()

        val logFile = destination.getLogFile()
        assertTrue(logFile.exists())
        assertEquals("logs", logFile.parentFile?.name)
        assertEquals(File(tempDir, "logs"), logFile.parentFile)
        assertEquals("dispensesure_logs.txt", logFile.name)
    }

    @Test
    fun `write appends rather than overwriting previous entries`() {
        val destination = FileLogDestination(context)

        destination.write(entry("first"))
        destination.write(entry("second"))
        destination.awaitIdleForTest()

        val content = destination.getLogFile().readText()
        assertTrue(content.contains("first"))
        assertTrue(content.contains("second"))
    }

    @Test
    fun `written entry is formatted with the structured log block`() {
        val destination = FileLogDestination(context)

        destination.write(entry("something failed"))
        destination.awaitIdleForTest()

        val content = destination.getLogFile().readText()
        assertTrue(content.contains("Level: ERROR"))
        assertTrue(content.contains("File: Foo.kt"))
        assertTrue(content.contains("Class: Foo"))
        assertTrue(content.contains("Method: bar"))
        assertTrue(content.contains("something failed"))
    }

    @Test
    fun `all entries accumulate in the single dispensesure_logs-txt file, no rotation`() {
        val destination = FileLogDestination(context)

        repeat(5) { i -> destination.write(entry("entry $i")) }
        destination.awaitIdleForTest()

        val logDir = File(tempDir, "logs")
        assertEquals(listOf("dispensesure_logs.txt"), logDir.list()?.toList())

        val content = destination.getLogFile().readText()
        (0 until 5).forEach { i -> assertTrue(content.contains("entry $i")) }
    }

    @Test
    fun `a write failure is caught and does not throw`() {
        // Point filesDir at a location that cannot be created (a file, not a directory) so the
        // logs directory creation fails inside writeInternal.
        val blockedPath = File(tempDir, "blocked").apply { writeText("not a directory") }
        every { context.filesDir } returns blockedPath

        val destination = FileLogDestination(context)

        // Must not throw or propagate the failure to the caller.
        destination.write(entry())
        destination.awaitIdleForTest()
    }
}
