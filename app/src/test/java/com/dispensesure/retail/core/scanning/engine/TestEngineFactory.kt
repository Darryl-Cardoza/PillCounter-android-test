package com.dispensesure.retail.core.scanning.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.dispensesure.retail.core.room.dao.UserDao
import com.dispensesure.retail.core.scanning.logic.GloveDetection
import com.dispensesure.retail.core.scanning.logic.PillDetectionModelLoader
import com.dispensesure.retail.core.scanning.logic.TrayColor
import com.dispensesure.retail.core.scanning.logic.TrayDetection
import com.dispensesure.retail.core.utils.common.LocationProvider
import com.dispensesure.retail.core.utils.logger.PerformanceLogger
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope

/** Builds real engines from test mocks and keeps the last one, so view model tests can drive it. */
class TestEngineFactory(
    private val context: Context,
    private val modelLoader: PillDetectionModelLoader,
    private val performanceLogger: PerformanceLogger,
    private val locationProvider: LocationProvider,
    private val userDao: UserDao,
    private val preferenceHelper: PreferenceHelper,
) : PillCountingEngine.Factory {

    lateinit var engine: PillCountingEngine
        private set

    override fun create(scope: CoroutineScope): PillCountingEngine =
        PillCountingEngine(
            scope = scope,
            context = context,
            modelLoader = modelLoader,
            performanceLogger = performanceLogger,
            locationProvider = locationProvider,
            userDao = userDao,
            preferenceHelper = preferenceHelper,
        ).also { createdEngine -> engine = createdEngine }
}

/** Puts a camera frame in place, as the analyzer does after each frame. */
fun PillCountingEngine.putFrameForTest(frame: Bitmap?) = engineField("currentFrameBitmap").set(this, frame)

fun PillCountingEngine.frameForTest(): Bitmap? = engineField("currentFrameBitmap").get(this) as Bitmap?

fun PillCountingEngine.lastAddSignatureForTest(): String? =
    engineField("lastAddedScanSignature").get(this) as String?

/** One analyzed frame with a single glove detection of [confidence]. */
fun PillCountingEngine.feedGloveFrameForTest(confidence: Float) = processDetections(
    count = 0,
    detections = emptyList(),
    trayDetections = emptyList(),
    gloveDetections = listOf(GloveDetection(RectF(), confidence, classId = 0, className = "gloves")),
    bitmap = mockk(relaxed = true),
    matrix = mockk(relaxed = true),
    previewWidth = 1, previewHeight = 1, imageWidth = 1, imageHeight = 1,
)

/** One analyzed frame with a complete tray of [color] in view. */
fun PillCountingEngine.feedTrayFrameForTest(color: TrayColor) = processDetections(
    count = 0,
    detections = emptyList(),
    trayDetections = listOf(TrayDetection(RectF(), 0.9f, trayColor = color)),
    gloveDetections = emptyList(),
    bitmap = mockk(relaxed = true),
    matrix = mockk(relaxed = true),
    previewWidth = 1, previewHeight = 1, imageWidth = 1, imageHeight = 1,
)

private fun engineField(name: String) =
    PillCountingEngine::class.java.getDeclaredField(name).apply { isAccessible = true }
