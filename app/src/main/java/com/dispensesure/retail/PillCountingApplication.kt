package com.dispensesure.retail

import android.app.Application
import androidx.camera.lifecycle.ProcessCameraProvider
import coil.Coil
import com.dispensesure.retail.core.faceAuth.data.OperatorNameProvider
import com.dispensesure.retail.core.utils.logger.AppLogger
import coil.ImageLoader
import com.dispensesure.retail.core.utils.coil.EncryptedImageFetcher
import com.dispensesure.retail.core.utils.common.SoundUtils
import com.dispensesure.retail.core.utils.logger.LogEvent
import com.dispensesure.retail.core.utils.logger.LoggerConfig
import com.dispensesure.retail.core.scanning.logic.PillDetectionModelLoader
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import javax.inject.Inject

@HiltAndroidApp
class PillCountingApplication : Application() {

    @Inject
    lateinit var modelLoader: PillDetectionModelLoader

    @Inject
    lateinit var operatorNameProvider: OperatorNameProvider

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logger = AppLogger("PillCountingApplication")

    override fun onCreate() {
        super.onCreate()
        // Must run before any other logging in this method, and before any other
        // singleton/DI-managed class has a chance to log during its own init.
        AppLogger.init(this)
//        FirebaseApp.initializeApp(this)

        // Keeps LoggerConfig.operatorName current so every subsequent log entry can be
        // attributed to whoever is actually operating the device (verified face user, else the
        // logged-in account — see OperatorNameProvider). A failure here (Room error, etc.) is
        // caught rather than crashing app start; the operator line just stays absent/stale.
        applicationScope.launch {
            operatorNameProvider.observe()
                .catch { e -> logger.e("Operator name observation failed", e, event = LogEvent.USER_FETCH_FAILED) }
                .collect { operatorName -> LoggerConfig.operatorName = operatorName.display() }
        }

        if (!OpenCVLoader.initLocal()) {
            logger.e("OpenCV initialization failed", event = LogEvent.MODEL_LOAD_FAILED)
        } else {
            logger.i("OpenCV initialized successfully")
        }

        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .components { add(EncryptedImageFetcher.Factory()) }
                .build()
        )

        // Preload BOTH models (pill + tray) in parallel on app start.
        // They are cached as singletons so the scanning screen gets them instantly.
        applicationScope.launch {
            logger.i("App start: triggering parallel model pre-load…")
            try {
                modelLoader.getOrLoadInterpreters()
                logger.i("App start: both models pre-loaded successfully!")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("App start: model pre-load failed", e, event = LogEvent.MODEL_LOAD_FAILED)
            }
        }

        // Pre-warm the shared TextToSpeech engine. Building one binds to the system
        // TTS service asynchronously (~0.5–1s before its onInit fires); doing it
        // here means the first step-title voiceover on the dispense/count screens
        // speaks immediately instead of after that init delay on screen entry.
        SoundUtils.prewarmTts(this)

        // Pre-warm CameraX. ProcessCameraProvider.getInstance(...) does the heavy
        // one-time init (libraries, camera2 interop, vendor extensions) and caches
        // a singleton. Triggering it at app start means CameraPreviewSection's
        // first bindToLifecycle() finds the provider already resolved instead of
        // paying that cost on the first tap of Dispense — the gap between tapping
        // Dispense and seeing live pixels shrinks by ~150–300ms on most devices.
        ProcessCameraProvider.getInstance(this)
    }
}
