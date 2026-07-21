package com.rite.pillcounting.core.utils.logger

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Debug
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for [PerformanceLogger].
 *
 * PerformanceLogger writes to a real log file under `context.getExternalFilesDir`, and reads
 * system info from Android framework classes (ActivityManager, Debug, BatteryManager, Build,
 * android.util.Log) that are not implemented on the plain JVM. Runs under Robolectric because
 * `getBatteryInfo()` constructs a real `IntentFilter`/`Intent` (framework objects, not just
 * interface calls) which the plain-JVM Android stub jar cannot construct — its constructors
 * unconditionally throw. We still point the "external files dir" at a real temp directory (so
 * file writes succeed) and mock/stub the framework calls with MockK so the class can be
 * exercised end to end without touching a real device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PerformanceLoggerTest {

    private lateinit var tempDir: File
    private lateinit var context: Context
    private lateinit var activityManager: ActivityManager

    @Before
    fun setup() {
        tempDir = kotlin.io.path.createTempDirectory(prefix = "perf_logger_test_").toFile()

        mockkStatic(Log::class)
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.println(any(), any(), any()) } returns 0
        every { Log.getStackTraceString(any()) } returns "stack"

        activityManager = mockk(relaxed = true)
        every { activityManager.getMemoryInfo(any()) } answers {
            val info = firstArg<ActivityManager.MemoryInfo>()
            info.totalMem = 8_000_000_000L
            info.availMem = 4_000_000_000L
            info.threshold = 100_000_000L
            info.lowMemory = false
        }

        context = mockk(relaxed = true)
        every { context.getExternalFilesDir(null) } returns tempDir
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns activityManager
        every { context.registerReceiver(null, any()) } returns null

        mockkStatic(Debug::class)
        every { Debug.getMemoryInfo(any()) } returns Unit
        every { Debug.getNativeHeapAllocatedSize() } returns 1_000_000L
        every { Debug.getNativeHeapSize() } returns 2_000_000L
        every { Debug.threadCpuTimeNanos() } returns 123_456L
    }

    @After
    fun tearDown() {
        unmockkAll()
        tempDir.deleteRecursively()
    }

    private fun createLogger(): PerformanceLogger = PerformanceLogger(context)

    // -------------------------------------------------------------------------
    // Construction / log file creation
    // -------------------------------------------------------------------------

    @Test
    fun `constructing logger creates performance_logs directory and log file`() {
        val logger = createLogger()

        val logFile = logger.getLogFile()
        assertTrue("log file should exist after init writes", logFile.exists())
        assertEquals("performance_logs", logFile.parentFile?.name)
        assertTrue(logFile.name.startsWith("model_performance_"))
        assertTrue(logFile.name.endsWith(".log"))
    }

    @Test
    fun `init writes session header info to log file`() {
        val logger = createLogger()
        val content = logger.getLogFile().readText()

        assertTrue(content.contains("PERFORMANCE MONITORING SESSION STARTED"))
        assertTrue(content.contains("Session ID:"))
        assertTrue(content.contains("Available Processors:"))
        assertTrue(content.contains("System Information:"))
    }

    @Test
    fun `getLogFile returns same file instance across calls due to lazy init`() {
        val logger = createLogger()
        val first = logger.getLogFile()
        val second = logger.getLogFile()
        assertEquals(first.absolutePath, second.absolutePath)
    }

    // -------------------------------------------------------------------------
    // logModelLoad
    // -------------------------------------------------------------------------

    @Test
    fun `logModelLoad without interpreter writes model load section without tensor info`() {
        val logger = createLogger()
        logger.logModelLoad(
            modelName = "detector.tflite",
            loadedOn = "CPU",
            interpreter = null,
            modelSizeBytes = 2048L,
            loadTimeMs = 150L,
            gpuDelegateEnabled = false
        )

        val content = logger.getLogFile().readText()
        assertTrue(content.contains("MODEL LOAD EVENT"))
        assertTrue(content.contains("Model Name: detector.tflite"))
        assertTrue(content.contains("Loaded On: CPU"))
        assertTrue(content.contains("GPU Delegate: DISABLED"))
        assertTrue(content.contains("Model Size: 2.00KB"))
        assertTrue(content.contains("Load Time: 150ms"))
        assertTrue(!content.contains("Model Tensor Information"))
    }

    @Test
    fun `logModelLoad with gpu enabled logs ENABLED`() {
        val logger = createLogger()
        logger.logModelLoad(
            modelName = "gpu_model",
            loadedOn = "GPU",
            interpreter = null,
            modelSizeBytes = 100L,
            loadTimeMs = 10L,
            gpuDelegateEnabled = true
        )

        val content = logger.getLogFile().readText()
        assertTrue(content.contains("GPU Delegate: ENABLED"))
    }

    @Test
    fun `logModelLoad includes memory battery and thermal info`() {
        val logger = createLogger()
        logger.logModelLoad(
            modelName = "m",
            loadedOn = "CPU",
            interpreter = null,
            modelSizeBytes = 1L,
            loadTimeMs = 1L
        )
        val content = logger.getLogFile().readText()
        assertTrue(content.contains("Memory State at Load:"))
        assertTrue(content.contains("Total RAM: 7.45GB"))
        assertTrue(content.contains("App Memory Usage:"))
        assertTrue(content.contains("Battery & Thermal:"))
        // No battery intent (registerReceiver returns null) -> defaults
        assertTrue(content.contains("Battery Level: 0%"))
        assertTrue(content.contains("Battery Health: Unknown"))
        assertTrue(content.contains("Charging: false"))
    }

    // -------------------------------------------------------------------------
    // logInference
    // -------------------------------------------------------------------------

    @Test
    fun `logInference computes total time and fps correctly`() {
        val logger = createLogger()
        logger.logInference(
            modelName = "detector",
            inferenceTimeMs = 40L,
            preprocessTimeMs = 5L,
            postprocessTimeMs = 5L,
            detectionCount = 3
        )
        val content = logger.getLogFile().readText()
        assertTrue(content.contains("INFERENCE EVENT"))
        assertTrue(content.contains("Total Time: 50ms"))
        assertTrue(content.contains("Detection Count: 3"))
        // 1000/50 = 20.00
        assertTrue(content.contains("FPS: 20.00"))
    }

    @Test
    fun `logInference with zero total time coerces divisor to avoid divide by zero`() {
        val logger = createLogger()
        logger.logInference(modelName = "m", inferenceTimeMs = 0L)
        val content = logger.getLogFile().readText()
        // total time is 0, coerced to 1 -> fps = 1000.00
        assertTrue(content.contains("Total Time: 0ms"))
        assertTrue(content.contains("FPS: 1000.00"))
    }

    @Test
    fun `logInference uses default optional parameters`() {
        val logger = createLogger()
        logger.logInference(modelName = "defaults", inferenceTimeMs = 10L)
        val content = logger.getLogFile().readText()
        assertTrue(content.contains("Preprocess Time: 0ms"))
        assertTrue(content.contains("Postprocess Time: 0ms"))
        assertTrue(content.contains("Detection Count: 0"))
    }

    // -------------------------------------------------------------------------
    // logPerformanceSnapshot
    // -------------------------------------------------------------------------

    @Test
    fun `logPerformanceSnapshot uses default tag SNAPSHOT`() {
        val logger = createLogger()
        logger.logPerformanceSnapshot()
        val content = logger.getLogFile().readText()
        assertTrue(content.contains("PERFORMANCE SNAPSHOT - SNAPSHOT"))
    }

    @Test
    fun `logPerformanceSnapshot uses custom tag when provided`() {
        val logger = createLogger()
        logger.logPerformanceSnapshot("PRE_SCAN")
        val content = logger.getLogFile().readText()
        assertTrue(content.contains("PERFORMANCE SNAPSHOT - PRE_SCAN"))
        assertTrue(content.contains("System Memory:"))
        assertTrue(content.contains("App Memory:"))
        assertTrue(content.contains("CPU:"))
        assertTrue(content.contains("Battery:"))
        assertTrue(content.contains("Thermal:"))
    }

    // -------------------------------------------------------------------------
    // logModelMemoryFootprint
    // -------------------------------------------------------------------------

    @Test
    fun `logModelMemoryFootprint formats estimated memory to two decimals`() {
        val logger = createLogger()
        logger.logModelMemoryFootprint("m", "CPU", 12.3456)
        val content = logger.getLogFile().readText()
        assertTrue(content.contains("MODEL MEMORY FOOTPRINT"))
        assertTrue(content.contains("Estimated Memory: 12.35 MB"))
    }

    // -------------------------------------------------------------------------
    // generateSummaryReport
    // -------------------------------------------------------------------------

    @Test
    fun `generateSummaryReport returns report containing key sections and is also written to file`() {
        val logger = createLogger()
        val report = logger.generateSummaryReport()

        assertTrue(report.contains("PERFORMANCE SUMMARY REPORT"))
        assertTrue(report.contains("Session ID:"))
        assertTrue(report.contains("Log File:"))
        assertTrue(report.contains("Current System State:"))

        val fileContent = logger.getLogFile().readText()
        assertTrue(fileContent.contains("PERFORMANCE SUMMARY REPORT"))
    }

    // -------------------------------------------------------------------------
    // clearOldLogs
    // -------------------------------------------------------------------------

    @Test
    fun `clearOldLogs deletes files older than cutoff and keeps recent ones`() {
        val logger = createLogger()
        val logDir = logger.getLogFile().parentFile!!

        val oldFile = File(logDir, "old.log").apply {
            writeText("old")
            setLastModified(System.currentTimeMillis() - (10L * 24 * 60 * 60 * 1000))
        }
        val recentFile = File(logDir, "recent.log").apply {
            writeText("recent")
            setLastModified(System.currentTimeMillis())
        }

        logger.clearOldLogs(daysToKeep = 7)

        assertTrue(!oldFile.exists())
        assertTrue(recentFile.exists())
    }

    @Test
    fun `clearOldLogs with default daysToKeep of 7 does not throw`() {
        val logger = createLogger()
        // Should simply complete without exception even with no extra files present.
        logger.clearOldLogs()
        assertTrue(logger.getLogFile().parentFile?.exists() == true)
    }

    @Test
    fun `clearOldLogs swallows exceptions when listing files fails`() {
        val logger = createLogger()
        // Delete the log directory entirely so parentFile.listFiles() returns null / errors are handled gracefully.
        logger.getLogFile().parentFile?.deleteRecursively()

        // Should not throw despite the directory being gone.
        logger.clearOldLogs(daysToKeep = 1)
    }

    // -------------------------------------------------------------------------
    // Battery info branch coverage (via logModelMemoryFootprint's simpler path is not enough;
    // use logPerformanceSnapshot which also logs battery info, driven by a real Intent).
    // -------------------------------------------------------------------------

    @Test
    fun `battery info reflects charging state and health when intent has data`() {
        val batteryIntent = mockk<Intent>(relaxed = true)
        every { context.registerReceiver(null, any()) } returns batteryIntent
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) } returns 80
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1) } returns 100
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) } returns 250
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) } returns 4200
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) } returns BatteryManager.BATTERY_STATUS_CHARGING
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) } returns BatteryManager.BATTERY_HEALTH_GOOD

        val logger = createLogger()
        logger.logPerformanceSnapshot()

        val content = logger.getLogFile().readText()
        assertTrue(content.contains("Level: 80%"))
        assertTrue(content.contains("Temperature: 25.0"))
        assertTrue(content.contains("Voltage: 4200mV"))
        assertTrue(content.contains("Charging: true"))
    }
}
