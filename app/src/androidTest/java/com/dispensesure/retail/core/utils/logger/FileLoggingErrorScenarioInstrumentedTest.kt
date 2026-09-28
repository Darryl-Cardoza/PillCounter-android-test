package com.dispensesure.retail.core.utils.logger

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dispensesure.retail.core.utils.logger.destination.FileLogDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs on a physical device/emulator against the real, wired-up [AppLogger]/[FileLogDestination]
 * — no fakes/mocks — covering the realistic ways an error scenario reaches the on-device log file.
 */
@RunWith(AndroidJUnit4::class)
class FileLoggingErrorScenarioInstrumentedTest {

    private val logger = AppLogger("FileLoggingErrorScenarioTest")

    @Before
    fun setup() {
        // The test app process runs PillCountingApplication.onCreate() first, so AppLogger is
        // already wired to a real FileLogDestination backed by real device storage.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        AppLogger.init(appContext)
    }

    @Test
    fun triggeringARealExceptionWritesAnErrorEntryToTheLogFile() {
        val marker = "scenario-${System.currentTimeMillis()}"

        try {
            triggerDivideByZero()
        } catch (e: ArithmeticException) {
            logger.e("Deliberate test failure: $marker", e)
        }

        val content = waitForContentContaining(readBackLogFile(), marker)

        assertTrue("Expected an ERROR entry for $marker", content.contains("Level: ERROR"))
        assertTrue("Expected the original exception type to be preserved", content.contains("ArithmeticException"))
    }

    @Test
    fun errorLoggedWithoutAThrowableWritesAPlainMessageBlockInsteadOfAStackTrace() {
        val marker = "message-only-${System.currentTimeMillis()}"

        logger.e("Deliberate message-only failure: $marker")

        val content = waitForContentContaining(readBackLogFile(), marker)
        val entryBlock = blockContaining(content, marker)

        assertTrue("Expected a plain Message: block", entryBlock.contains("Message:\nDeliberate message-only failure: $marker"))
        assertFalse("Should not fabricate a stack trace when there is no throwable", entryBlock.contains("Stack Trace:"))
    }

    @Test
    fun aWarnBelowTheDefaultErrorMinimumLevelIsFilteredOutOfTheFile() {
        val marker = "warn-filtered-${System.currentTimeMillis()}"
        val logFile = readBackLogFile()
        val sizeBefore = if (logFile.exists()) logFile.length() else 0L

        logger.w("Deliberate warning that should be filtered: $marker")
        awaitIdleForTest()

        val content = if (logFile.exists()) logFile.readText() else ""
        assertFalse("A WARN entry must not reach the file while minimumLogLevel is ERROR", content.contains(marker))
        assertEquals("File must be untouched by the filtered-out write", sizeBefore, if (logFile.exists()) logFile.length() else 0L)
    }

    @Test
    fun anEntryAppendsRatherThanOverwritingWhatAFreshAppProcessAlreadyWrote() {
        val firstMarker = "restart-before-${System.currentTimeMillis()}"
        val secondMarker = "restart-after-${System.currentTimeMillis()}"

        logger.e("First entry: $firstMarker")
        waitForContentContaining(readBackLogFile(), firstMarker)

        // A fresh FileLogDestination instance, same as a new process would construct on launch.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        FileLogDestination(appContext).write(
            LogEntry(
                timestampMillis = System.currentTimeMillis(),
                level = LogLevel.ERROR,
                fileName = "Restart.kt",
                className = "Restart",
                methodName = "afterRelaunch",
                message = "Second entry: $secondMarker"
            )
        )

        val content = waitForContentContaining(readBackLogFile(), secondMarker)
        assertTrue("Entry written before the simulated restart must survive", content.contains(firstMarker))
        assertTrue("Entry written after the simulated restart must be appended", content.contains(secondMarker))
    }

    @Test
    fun logsDirectoryIsRecreatedAutomaticallyIfItWasDeletedSincelastWrite() {
        val marker = "recreated-dir-${System.currentTimeMillis()}"
        val logDir = readBackLogFile().parentFile!!

        logDir.deleteRecursively()
        assertFalse("Logs directory should be gone before the write", logDir.exists())

        logger.e("Deliberate failure after deleting Logs/: $marker")

        val content = waitForContentContaining(readBackLogFile(), marker)
        assertTrue("Directory + file must be recreated on the next write", content.contains(marker))
    }

    @Test
    fun concurrentErrorsFromMultipleThreadsAreAllWrittenWithoutInterleavingOrLoss() {
        val runId = System.currentTimeMillis()
        val threadCount = 10
        val startLine = CountDownLatch(1)
        val finished = CountDownLatch(threadCount)

        repeat(threadCount) { i ->
            Thread {
                startLine.await()
                logger.e("Concurrent failure $runId-$i")
                finished.countDown()
            }.start()
        }
        startLine.countDown()
        assertTrue("All logging threads must finish", finished.await(5, TimeUnit.SECONDS))

        val content = waitForContentContaining(readBackLogFile(), "$runId-${threadCount - 1}")
        repeat(threadCount) { i ->
            val marker = "Concurrent failure $runId-$i"
            val occurrences = Regex(Regex.escape(marker)).findAll(content).count()
            assertEquals("Entry for thread $i must appear exactly once, not dropped or duplicated", 1, occurrences)
        }
    }

    private fun triggerDivideByZero(): Int {
        val zero = 0
        return 1 / zero
    }

    private fun readBackLogFile(): File {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        return File(File(appContext.getExternalFilesDir(null), "Logs"), LOG_FILE_NAME)
    }

    /** No public hook to flush the singleton destination, so just give its background thread time. */
    private fun awaitIdleForTest(timeoutMillis: Long = 500) = Thread.sleep(timeoutMillis)

    /** The file accumulates entries across every test, so isolate just the block for [marker]. */
    private fun blockContaining(content: String, marker: String): String {
        val separator = "=".repeat(60)
        return content.split(separator).firstOrNull { it.contains(marker) } ?: ""
    }

    /** Writes happen on a background thread, so poll briefly instead of racing the flush. */
    private fun waitForContentContaining(file: File, marker: String, timeoutMillis: Long = 3000): String {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (file.exists() && file.readText().contains(marker)) return file.readText()
            Thread.sleep(50)
        }
        return if (file.exists()) file.readText() else ""
    }

    private companion object {
        const val LOG_FILE_NAME = "dispensesure_logs.txt"
    }
}
