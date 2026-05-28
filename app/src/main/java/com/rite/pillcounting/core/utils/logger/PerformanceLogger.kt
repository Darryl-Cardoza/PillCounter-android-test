package com.rite.pillcounting.core.utils.logger

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Comprehensive performance logging system for TensorFlow Lite model monitoring.
 *
 * Tracks:
 * - Model loading (which model, CPU/GPU, memory footprint)
 * - Inference performance (timing, throughput)
 * - System resources (RAM, CPU, temperature)
 * - Battery performance
 * - Thermal conditions
 *
 * Logs are written to a persistent file for offline analysis and report generation.
 */
@Singleton
class PerformanceLogger @Inject constructor(
    private val context: Context
) {

    private val _logFile: File by lazy {
        val logDir = File(context.getExternalFilesDir(null), "performance_logs")
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        File(logDir, "model_performance_${getCurrentDateForFile()}.log")
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val sessionId = UUID.randomUUID().toString().substring(0, 8)

    private val activityManager: ActivityManager by lazy {
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    }

    companion object {
        private const val TAG = "PerformanceLogger"
        private val LOG_SEPARATOR = "=".repeat(80)
        private val SECTION_SEPARATOR = "-".repeat(80)
    }

    init {
        writeLog("╔═══════════════════════════════════════════════════════════════════════════════╗")
        writeLog("║                    PERFORMANCE MONITORING SESSION STARTED                     ║")
        writeLog("╚═══════════════════════════════════════════════════════════════════════════════╝")
        writeLog("Session ID: $sessionId")
        writeLog("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        writeLog("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        writeLog("CPU Architecture: ${Build.SUPPORTED_ABIS.joinToString()}")
        writeLog("Available Processors: ${Runtime.getRuntime().availableProcessors()}")
        writeLog(LOG_SEPARATOR)
        logSystemInfo()
    }

    /**
     * Log model loading event with detailed resource allocation info
     */
    fun logModelLoad(
        modelName: String,
        loadedOn: String, // "CPU" or "GPU"
        interpreter: Interpreter?,
        modelSizeBytes: Long,
        loadTimeMs: Long,
        gpuDelegateEnabled: Boolean = false
    ) {
        writeLog("\n$SECTION_SEPARATOR")
        writeLog("MODEL LOAD EVENT")
        writeLog(SECTION_SEPARATOR)
        writeLog("Model Name: $modelName")
        writeLog("Loaded On: $loadedOn")
        writeLog("GPU Delegate: ${if (gpuDelegateEnabled) "ENABLED" else "DISABLED"}")
        writeLog("Model Size: ${formatBytes(modelSizeBytes)}")
        writeLog("Load Time: ${loadTimeMs}ms")

        // Memory snapshot at load time
        val memInfo = getMemoryInfo()
        writeLog("\nMemory State at Load:")
        writeLog("  Total RAM: ${formatBytes(memInfo.totalMemory)}")
        writeLog("  Available RAM: ${formatBytes(memInfo.availableMemory)}")
        writeLog("  Used RAM: ${formatBytes(memInfo.totalMemory - memInfo.availableMemory)}")
        writeLog("  RAM Usage %: ${"%.2f".format(((memInfo.totalMemory - memInfo.availableMemory) * 100.0 / memInfo.totalMemory))}%")
        writeLog("  Low Memory: ${memInfo.lowMemory}")
        writeLog("  Memory Threshold: ${formatBytes(memInfo.threshold)}")

        // App memory usage
        val appMemInfo = getAppMemoryInfo()
        writeLog("\nApp Memory Usage:")
        writeLog("  Java Heap Used: ${formatBytes(appMemInfo.javaHeapUsed)}")
        writeLog("  Java Heap Max: ${formatBytes(appMemInfo.javaHeapMax)}")
        writeLog("  Java Heap Free: ${formatBytes(appMemInfo.javaHeapFree)}")
        writeLog("  Native Heap Used: ${formatBytes(appMemInfo.nativeHeapUsed)}")
        writeLog("  Native Heap Size: ${formatBytes(appMemInfo.nativeHeapSize)}")
        writeLog("  Total PSS: ${formatBytes(appMemInfo.totalPss * 1024L)}")

        // Interpreter info
        interpreter?.let {
            try {
                writeLog("\nModel Tensor Information:")
                writeLog("  Input Tensors: ${it.inputTensorCount}")
                for (i in 0 until it.inputTensorCount) {
                    val tensor = it.getInputTensor(i)
                    writeLog("    Input[$i]: shape=${tensor.shape().contentToString()}, type=${tensor.dataType()}")
                }
                writeLog("  Output Tensors: ${it.outputTensorCount}")
                for (i in 0 until it.outputTensorCount) {
                    val tensor = it.getOutputTensor(i)
                    writeLog("    Output[$i]: shape=${tensor.shape().contentToString()}, type=${tensor.dataType()}")
                }
            } catch (e: Exception) {
                writeLog("  Error reading tensor info: ${e.message}")
            }
        }

        // Battery and thermal
        val batteryInfo = getBatteryInfo()
        writeLog("\nBattery & Thermal:")
        writeLog("  Battery Level: ${batteryInfo.level}%")
        writeLog("  Battery Temperature: ${batteryInfo.temperature}°C")
        writeLog("  Battery Voltage: ${batteryInfo.voltage}mV")
        writeLog("  Charging: ${batteryInfo.isCharging}")
        writeLog("  Battery Health: ${batteryInfo.health}")

        val thermalInfo = getThermalInfo()
        writeLog("  CPU Temperature: ${thermalInfo.cpuTemp}°C")
        writeLog("  Thermal Throttling: ${thermalInfo.thermalStatus}")

        writeLog(SECTION_SEPARATOR)
    }

    /**
     * Log inference/detection event with performance metrics
     */
    fun logInference(
        modelName: String,
        inferenceTimeMs: Long,
        preprocessTimeMs: Long = 0L,
        postprocessTimeMs: Long = 0L,
        detectionCount: Int = 0
    ) {
        writeLog("\n$SECTION_SEPARATOR")
        writeLog("INFERENCE EVENT")
        writeLog(SECTION_SEPARATOR)
        writeLog("Model: $modelName")
        writeLog("Timestamp: ${getCurrentTimestamp()}")
        writeLog("Preprocess Time: ${preprocessTimeMs}ms")
        writeLog("Inference Time: ${inferenceTimeMs}ms")
        writeLog("Postprocess Time: ${postprocessTimeMs}ms")
        writeLog("Total Time: ${preprocessTimeMs + inferenceTimeMs + postprocessTimeMs}ms")
        writeLog("Detection Count: $detectionCount")
        writeLog("FPS: ${"%.2f".format(1000.0 / (preprocessTimeMs + inferenceTimeMs + postprocessTimeMs).coerceAtLeast(1))}")

        // System snapshot during inference
        val memInfo = getMemoryInfo()
        writeLog("Available RAM: ${formatBytes(memInfo.availableMemory)} (${((memInfo.availableMemory * 100.0) / memInfo.totalMemory).toInt()}%)")

        val cpuUsage = getCpuUsage()
        writeLog("App CPU Usage: ${"%.1f".format(cpuUsage)}%")

        writeLog(SECTION_SEPARATOR)
    }

    /**
     * Log periodic performance snapshot (call every N seconds during operation)
     */
    fun logPerformanceSnapshot(tag: String = "SNAPSHOT") {
        writeLog("\n$SECTION_SEPARATOR")
        writeLog("PERFORMANCE SNAPSHOT - $tag")
        writeLog(SECTION_SEPARATOR)
        writeLog("Timestamp: ${getCurrentTimestamp()}")

        // Memory
        val memInfo = getMemoryInfo()
        val appMemInfo = getAppMemoryInfo()
        writeLog("\nSystem Memory:")
        writeLog("  Total: ${formatBytes(memInfo.totalMemory)}")
        writeLog("  Available: ${formatBytes(memInfo.availableMemory)} (${((memInfo.availableMemory * 100.0) / memInfo.totalMemory).toInt()}%)")
        writeLog("  Low Memory Warning: ${memInfo.lowMemory}")

        writeLog("\nApp Memory:")
        writeLog("  Java Heap: ${formatBytes(appMemInfo.javaHeapUsed)} / ${formatBytes(appMemInfo.javaHeapMax)}")
        writeLog("  Native Heap: ${formatBytes(appMemInfo.nativeHeapUsed)} / ${formatBytes(appMemInfo.nativeHeapSize)}")
        writeLog("  Total PSS: ${formatBytes(appMemInfo.totalPss * 1024L)}")

        // CPU
        val cpuUsage = getCpuUsage()
        writeLog("\nCPU:")
        writeLog("  App CPU Usage: ${"%.1f".format(cpuUsage)}%")
        writeLog("  Available Processors: ${Runtime.getRuntime().availableProcessors()}")
        writeLog("  Note: System-wide CPU not accessible on Android 8+ (SELinux restrictions)")

        // Battery
        val batteryInfo = getBatteryInfo()
        writeLog("\nBattery:")
        writeLog("  Level: ${batteryInfo.level}%")
        writeLog("  Temperature: ${batteryInfo.temperature}°C")
        writeLog("  Voltage: ${batteryInfo.voltage}mV")
        writeLog("  Charging: ${batteryInfo.isCharging}")

        // Thermal
        val thermalInfo = getThermalInfo()
        writeLog("\nThermal:")
        writeLog("  CPU Temp: ${thermalInfo.cpuTemp}°C")
        writeLog("  Status: ${thermalInfo.thermalStatus}")

        writeLog(SECTION_SEPARATOR)
    }

    /**
     * Log model memory footprint analysis
     */
    fun logModelMemoryFootprint(
        modelName: String,
        loadedOn: String,
        estimatedMemoryMB: Double
    ) {
        writeLog("\n$SECTION_SEPARATOR")
        writeLog("MODEL MEMORY FOOTPRINT")
        writeLog(SECTION_SEPARATOR)
        writeLog("Model: $modelName")
        writeLog("Device: $loadedOn")
        writeLog("Estimated Memory: ${"%.2f".format(estimatedMemoryMB)} MB")
        writeLog(SECTION_SEPARATOR)
    }

    /**
     * Generate summary report
     */
    fun generateSummaryReport(): String {
        val summary = StringBuilder()
        summary.append("\n")
        summary.append("╔═══════════════════════════════════════════════════════════════════════════════╗\n")
        summary.append("║                           PERFORMANCE SUMMARY REPORT                          ║\n")
        summary.append("╚═══════════════════════════════════════════════════════════════════════════════╝\n")
        summary.append("Session ID: $sessionId\n")
        summary.append("Report Generated: ${getCurrentTimestamp()}\n")
        summary.append("Log File: ${_logFile.absolutePath}\n")
        summary.append("Log Size: ${formatBytes(_logFile.length())}\n")

        val memInfo = getMemoryInfo()
        val appMemInfo = getAppMemoryInfo()
        val batteryInfo = getBatteryInfo()
        val thermalInfo = getThermalInfo()

        summary.append("\nCurrent System State:\n")
        summary.append("  RAM Available: ${formatBytes(memInfo.availableMemory)}\n")
        summary.append("  App Memory: ${formatBytes(appMemInfo.totalPss * 1024L)}\n")
        summary.append("  CPU Usage: ${"%.1f".format(getCpuUsage())}%\n")
        summary.append("  Battery: ${batteryInfo.level}% @ ${batteryInfo.temperature}°C\n")
        summary.append("  Thermal: ${thermalInfo.cpuTemp}°C (${thermalInfo.thermalStatus})\n")
        summary.append(LOG_SEPARATOR)

        val report = summary.toString()
        writeLog(report)
        return report
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Private Helper Methods
    // ═══════════════════════════════════════════════════════════════════════════════

    private fun writeLog(message: String) {
        val timestamp = getCurrentTimestamp()
        val logMessage = "[$timestamp] $message"

        // Write to file
        try {
            BufferedWriter(FileWriter(_logFile, true)).use { writer ->
                writer.write(logMessage)
                writer.newLine()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to log file", e)
        }

        // Also log to Logcat for debugging
        Log.d(TAG, message)
    }

    private fun getCurrentTimestamp(): String {
        return dateFormat.format(Date())
    }

    private fun getCurrentDateForFile(): String {
        val fileFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return fileFormat.format(Date())
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${"%.2f".format(bytes / 1024.0)}KB"
            bytes < 1024 * 1024 * 1024 -> "${"%.2f".format(bytes / (1024.0 * 1024.0))}MB"
            else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))}GB"
        }
    }

    private fun logSystemInfo() {
        writeLog("\nSystem Information:")
        writeLog("  Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        writeLog("  Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        writeLog("  Build: ${Build.FINGERPRINT}")
        writeLog("  CPU ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        writeLog("  Kernel: ${System.getProperty("os.version")}")

        val memInfo = getMemoryInfo()
        writeLog("\nInitial Memory State:")
        writeLog("  Total RAM: ${formatBytes(memInfo.totalMemory)}")
        writeLog("  Available RAM: ${formatBytes(memInfo.availableMemory)}")

        val batteryInfo = getBatteryInfo()
        writeLog("\nInitial Battery State:")
        writeLog("  Level: ${batteryInfo.level}%")
        writeLog("  Temperature: ${batteryInfo.temperature}°C")
        writeLog(SECTION_SEPARATOR)
    }

    private data class MemoryInfo(
        val totalMemory: Long,
        val availableMemory: Long,
        val threshold: Long,
        val lowMemory: Boolean
    )

    private fun getMemoryInfo(): MemoryInfo {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return MemoryInfo(
            totalMemory = memInfo.totalMem,
            availableMemory = memInfo.availMem,
            threshold = memInfo.threshold,
            lowMemory = memInfo.lowMemory
        )
    }

    private data class AppMemoryInfo(
        val javaHeapUsed: Long,
        val javaHeapMax: Long,
        val javaHeapFree: Long,
        val nativeHeapUsed: Long,
        val nativeHeapSize: Long,
        val totalPss: Long
    )

    private fun getAppMemoryInfo(): AppMemoryInfo {
        val runtime = Runtime.getRuntime()
        val debugMemInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(debugMemInfo)

        return AppMemoryInfo(
            javaHeapUsed = runtime.totalMemory() - runtime.freeMemory(),
            javaHeapMax = runtime.maxMemory(),
            javaHeapFree = runtime.freeMemory(),
            nativeHeapUsed = Debug.getNativeHeapAllocatedSize(),
            nativeHeapSize = Debug.getNativeHeapSize(),
            totalPss = debugMemInfo.totalPss.toLong()
        )
    }

    private data class BatteryInfo(
        val level: Int,
        val temperature: Float,
        val voltage: Int,
        val isCharging: Boolean,
        val health: String
    )

    private fun getBatteryInfo(): BatteryInfo {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 0

        val temp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val tempCelsius = if (temp > 0) temp / 10f else 0f

        val voltage = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1

        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

        val health = batteryIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val healthStr = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Unknown"
        }

        return BatteryInfo(
            level = batteryPct,
            temperature = tempCelsius,
            voltage = voltage,
            isCharging = isCharging,
            health = healthStr
        )
    }

    private data class ThermalInfo(
        val cpuTemp: Float,
        val thermalStatus: String
    )

    private fun getThermalInfo(): ThermalInfo {
        var cpuTemp = 0f

        // Try to read CPU temperature from thermal zone files
        try {
            val thermalFiles = arrayOf(
                "/sys/class/thermal/thermal_zone0/temp",
                "/sys/class/thermal/thermal_zone1/temp",
                "/sys/devices/virtual/thermal/thermal_zone0/temp"
            )

            for (path in thermalFiles) {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    val tempStr = file.readText().trim()
                    val temp = tempStr.toFloatOrNull()
                    if (temp != null) {
                        cpuTemp = if (temp > 1000) temp / 1000f else temp
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read thermal zone", e)
        }

        val thermalStatus = if (cpuTemp > 80) {
            "CRITICAL"
        } else if (cpuTemp > 70) {
            "HIGH"
        } else if (cpuTemp > 60) {
            "MODERATE"
        } else {
            "NORMAL"
        }

        return ThermalInfo(cpuTemp, thermalStatus)
    }

    // CPU usage tracking variables
    private var lastAppCpuTime = 0L
    private var lastTimestamp = 0L

    /**
     * Get CPU usage percentage.
     *
     * Note: /proc/stat is restricted on Android 8+ (SELinux), so we use alternative methods:
     * 1. Try /proc/self/stat for app CPU usage
     * 2. Fall back to Debug.threadCpuTimeNanos() for app threads
     * 3. Calculate approximate CPU usage based on app's CPU time delta
     */
    private fun getCpuUsage(): Float {
        try {
            // Method 1: Try reading app's own CPU usage from /proc/self/stat (should be accessible)
            try {
                val statFile = RandomAccessFile("/proc/self/stat", "r")
                val statLine = statFile.readLine()
                statFile.close()

                val tokens = statLine.split(" ")
                if (tokens.size >= 17) {
                    // tokens[13] = utime (user mode jiffies)
                    // tokens[14] = stime (kernel mode jiffies)
                    val utime = tokens[13].toLongOrNull() ?: 0L
                    val stime = tokens[14].toLongOrNull() ?: 0L
                    val totalAppTime = utime + stime
                    val currentTime = System.currentTimeMillis()

                    if (lastAppCpuTime > 0 && lastTimestamp > 0) {
                        val timeDelta = currentTime - lastTimestamp
                        val cpuDelta = totalAppTime - lastAppCpuTime

                        if (timeDelta > 0) {
                            // Convert jiffies to percentage
                            // 1 jiffy = 10ms typically, but varies by CONFIG_HZ
                            // Assume 100 HZ (1 jiffy = 10ms)
                            val cpuTimeMs = cpuDelta * 10
                            val numCores = Runtime.getRuntime().availableProcessors()
                            val maxCpuTime = timeDelta * numCores
                            val usage = (cpuTimeMs * 100.0f / maxCpuTime).coerceIn(0f, 100f * numCores)

                            lastAppCpuTime = totalAppTime
                            lastTimestamp = currentTime
                            return usage
                        }
                    }

                    lastAppCpuTime = totalAppTime
                    lastTimestamp = currentTime
                }
            } catch (e: Exception) {
                // /proc/self/stat also restricted or not available
                Log.d(TAG, "/proc/self/stat not accessible: ${e.message}")
            }

            // Method 2: Use Debug API for thread CPU time (app-level only)
            val currentThreadTime = Debug.threadCpuTimeNanos()
            val currentTime = System.currentTimeMillis()

            if (lastAppCpuTime > 0 && lastTimestamp > 0) {
                val timeDelta = (currentTime - lastTimestamp) * 1_000_000L // Convert to nanos
                val cpuDelta = currentThreadTime - lastAppCpuTime

                if (timeDelta > 0) {
                    // Calculate percentage based on current thread
                    val usage = (cpuDelta * 100.0f / timeDelta).coerceIn(0f, 100f)

                    lastAppCpuTime = currentThreadTime
                    lastTimestamp = currentTime
                    return usage
                }
            }

            lastAppCpuTime = currentThreadTime
            lastTimestamp = currentTime
            return 0f

        } catch (e: Exception) {
            Log.w(TAG, "Could not calculate CPU usage: ${e.message}")
            return 0f
        }
    }

    /**
     * Get the log file for external access
     */
    fun getLogFile(): File = _logFile

    /**
     * Clear old logs (call periodically or on app start)
     */
    fun clearOldLogs(daysToKeep: Int = 7) {
        try {
            val logDir = _logFile.parentFile ?: return
            val now = System.currentTimeMillis()
            val cutoff = now - (daysToKeep * 24 * 60 * 60 * 1000L)

            logDir.listFiles()?.forEach { file ->
                if (file.lastModified() < cutoff) {
                    file.delete()
                    Log.d(TAG, "Deleted old log: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear old logs", e)
        }
    }
}


