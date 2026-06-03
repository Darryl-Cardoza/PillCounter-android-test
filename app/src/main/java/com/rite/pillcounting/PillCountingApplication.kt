package com.rite.pillcounting

import android.app.Application
import android.util.Log
import androidx.camera.lifecycle.ProcessCameraProvider
import coil.Coil
import com.rite.pillcounting.core.utils.logger.AppLogger
import coil.ImageLoader
import com.google.firebase.FirebaseApp
import com.rite.pillcounting.core.utils.coil.EncryptedImageFetcher
import com.rite.pillcounting.core.scanning.logic.PillDetectionModelLoader
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import javax.inject.Inject

@HiltAndroidApp
class PillCountingApplication : Application() {

    @Inject
    lateinit var modelLoader: PillDetectionModelLoader
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logger = AppLogger("PillCountingApplication")

    override fun onCreate() {
        super.onCreate()
//        FirebaseApp.initializeApp(this)

        if (!OpenCVLoader.initLocal()) {
            logger.e("OpenCV initialization failed")
        } else {
            logger.i("OpenCV initialized successfully")
        }

        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .components { add(EncryptedImageFetcher.Factory()) }
                .build()
        )

        // Pre-load BOTH models (pill + tray) in parallel on app start.
        // They are cached as singletons so the scanning screen gets them instantly.
        applicationScope.launch {
            logger.i("App start: triggering parallel model pre-load…")
            try {
                modelLoader.getOrLoadInterpreters()
                logger.i("App start: both models pre-loaded successfully!")
            } catch (e: Exception) {
                logger.e("App start: model pre-load failed", e)
            }
        }

        // Pre-warm CameraX. ProcessCameraProvider.getInstance(...) does the heavy
        // one-time init (libraries, camera2 interop, vendor extensions) and caches
        // a singleton. Triggering it at app start means CameraPreviewSection's
        // first bindToLifecycle() finds the provider already resolved instead of
        // paying that cost on the first tap of Dispense — the gap between tapping
        // Dispense and seeing live pixels shrinks by ~150–300ms on most devices.
        ProcessCameraProvider.getInstance(this)
    }
}