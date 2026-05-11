package com.rite.pillcounting

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.rite.pillcounting.core.utils.logger.PerformanceLogger
import com.rite.pillcounting.feature.pillCountScan.domain.PillDetectionModelLoader
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class PillCountingApplication : Application() {

    @Inject
    lateinit var modelLoader: PillDetectionModelLoader

    @Inject
    lateinit var performanceLogger: PerformanceLogger

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)

        // Clean up old performance logs (keep last 7 days)
        applicationScope.launch {
            try {
                performanceLogger.clearOldLogs(daysToKeep = 7)
                Log.i("PerformanceLogger", "Old logs cleaned up")
            } catch (e: Exception) {
                Log.e("PerformanceLogger", "Failed to clean old logs", e)
            }
        }

        // Pre-load BOTH models (pill + tray) in parallel on app start.
        // They are cached as singletons so the scanning screen gets them instantly.
        applicationScope.launch {
            Log.i("LoadModel", "App start: triggering parallel model pre-load…")
            try {
                modelLoader.getOrLoadInterpreters()
                Log.i("LoadModel", "App start: both models pre-loaded successfully!")
                Log.i("PerformanceLogger", "Performance log file: ${performanceLogger.getLogFile().absolutePath}")
            } catch (e: Exception) {
                Log.e("LoadModel", "App start: model pre-load failed", e)
            }
        }
    }
}