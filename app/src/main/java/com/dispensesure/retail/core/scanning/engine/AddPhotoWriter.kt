package com.dispensesure.retail.core.scanning.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.dispensesure.retail.R
import com.dispensesure.retail.core.room.dao.UserDao
import com.dispensesure.retail.core.utils.common.HelperFunctions.saveBitmapToFile
import com.dispensesure.retail.core.utils.common.LocationProvider
import com.dispensesure.retail.core.utils.common.OverlayUtils
import com.dispensesure.retail.core.utils.common.PhotoInfo
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * Writes the ADD photo: crop to tray + chute, pills and info strip drawn, saved as a JPEG,
 * plus the unmarked crop when asked. Keeps the last known location for the strip.
 */
class AddPhotoWriter(
    private val scope: CoroutineScope,
    private val context: Context,
    private val locationProvider: LocationProvider,
    private val userDao: UserDao,
    private val preferenceHelper: PreferenceHelper,
) {

    private val logger = AppLogger("PillCountingEngine")
    // Last looked-up location for the photo strip; Add never waits for a fresh one.
    @Volatile private var cachedLocation: String? = null
    private var locationJob: Job? = null

    /**
     * Draws the pills and the info strip on a [PillCountingEngine.captureCount] frame and saves it as a JPEG,
     * plus the unmarked crop when [keepRawCrop]. Recycles the frame.
     */
    suspend fun write(
        captured: CaptureResult.Captured,
        label: PhotoLabel,
        keepRawCrop: Boolean,
        grayscale: Boolean,
    ): SavedPhoto = withContext(Dispatchers.IO) {
        try {
            val userId = preferenceHelper.getUserId().orEmpty()
            val loggedInUser = userDao.getByUserId(userId)
            // No lookup finished yet: show the fallback rather than wait.
            val location = cachedLocation ?: context.getString(R.string.location_unavailable)

            val countedFrame = captured.frame

            val drugImage = label.drugImagePath?.takeIf { drugImagePath -> drugImagePath.isNotBlank() }
                ?.let { drugImagePath -> runCatching { BitmapFactory.decodeFile(drugImagePath) }.getOrNull() }
                ?: drugImagePlaceholder()

            // Crop to tray + chute when a complete tray is in view; else keep the full frame.
            val frameWidth = countedFrame.width
            val frameHeight = countedFrame.height
            val trayCropRegion = OverlayUtils.savedPhotoCropRegion(captured.trays, frameWidth, frameHeight)
            val croppedPhoto = if (trayCropRegion != null) {
                Bitmap.createBitmap(
                    countedFrame, trayCropRegion.left, trayCropRegion.top,
                    trayCropRegion.width(), trayCropRegion.height(),
                )
            } else countedFrame
            val pillsInPhoto = if (trayCropRegion != null) {
                OverlayUtils.pillsInCrop(captured.filteredPills, trayCropRegion, frameWidth, frameHeight)
            } else captured.filteredPills

            val photoInfo = PhotoInfo(
                drugName = label.drugName,
                drugImage = drugImage,
                isStockCount = label.isStockCount,
                requestedNdc = label.requestedNdc,
                dispensedNdc = label.dispensedNdc,
                lotNumber = label.lotNumber,
                serialNumber = label.serialNumber,
                expirationDate = label.expirationDate,
                count = captured.count,
                rxRefill = label.rxRefill,
                stepLabel = label.stepLabel,
                userName = listOfNotNull(loggedInUser?.fName, loggedInUser?.lName).joinToString(" "),
                location = location,
                timestamp = System.currentTimeMillis(),
                region = label.region,
            )
            val photoWithStrip = try {
                OverlayUtils.drawDetectionsOnBitmap(
                    bitmap = croppedPhoto,
                    detectedPills = pillsInPhoto,
                    info = photoInfo,
                    res = context.resources,
                    scaleRefSide = min(frameWidth, frameHeight),
                    // Same breakpoint as UserInterfaceUtils.isTablet(), read outside Compose.
                    isPhone = context.resources.configuration.smallestScreenWidthDp <
                        UserInterfaceUtils.TABLET_BREAKPOINT_DP,
                )
            } catch (error: Exception) {
                logger.e("Overlay drawing failed, using bitmap without overlay", error, event = LogEvent.PILL_COUNT_FAILED)
                croppedPhoto
            }
            drugImage?.recycle()

            val fileTimestamp = System.currentTimeMillis()
            val photoPath = try {
                if (!photoWithStrip.isRecycled) {
                    saveBitmapToFile(
                        context,
                        photoWithStrip,
                        "txn_detail_$fileTimestamp.jpg",
                        "transaction_details",
                        grayscale = grayscale,
                    )
                } else null
            } catch (error: Exception) {
                logger.e("Failed saving bitmap", error, event = LogEvent.FILE_WRITE_ERROR)
                null
            }
            // The crop with nothing drawn on it, when the caller needs it.
            val rawPhotoPath = if (keepRawCrop) {
                try {
                    if (!croppedPhoto.isRecycled) {
                        saveBitmapToFile(
                            context,
                            croppedPhoto,
                            "txn_detail_${fileTimestamp}_raw.jpg",
                            "transaction_details",
                        )
                    } else null
                } catch (error: Exception) {
                    logger.e("Failed saving raw bitmap", error, event = LogEvent.FILE_WRITE_ERROR)
                    null
                }
            } else null
            // A new bitmap, unless drawing failed and fell back to the crop (recycled below).
            if (photoWithStrip !== croppedPhoto && !photoWithStrip.isRecycled) photoWithStrip.recycle()
            // createBitmap can hand back the source itself when the crop is the whole frame.
            if (croppedPhoto !== countedFrame && !croppedPhoto.isRecycled) croppedPhoto.recycle()
            if (!countedFrame.isRecycled) countedFrame.recycle()

            SavedPhoto(imagePath = photoPath, rawImagePath = rawPhotoPath)
        } finally {
            // Runs on a failed save too, so the next Add still gets a fresh location.
            refreshLocation()
        }
    }

    /** Refreshes [cachedLocation] in the background; skipped while a lookup is running. */
    fun refreshLocation() {
        if (locationJob?.isActive == true) return
        locationJob = scope.launch(Dispatchers.IO) {
            cachedLocation = locationProvider.getCurrentLocationAsString()
        }
    }

    /** Scanning-screen drug-image fallback, as a bitmap for the saved photo (keeps its aspect). */
    private fun drugImagePlaceholder(): Bitmap? =
        ContextCompat.getDrawable(context, R.drawable.prescription_icon)?.let { placeholderDrawable ->
            val placeholderHeight = if (placeholderDrawable.intrinsicWidth > 0) {
                300 * placeholderDrawable.intrinsicHeight / placeholderDrawable.intrinsicWidth
            } else 225
            placeholderDrawable.toBitmap(300, placeholderHeight.coerceAtLeast(1))
        }
}
