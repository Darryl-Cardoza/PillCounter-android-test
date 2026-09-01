package com.rite.pillcounting.core.scanning.presentation.viewmodel

import android.app.Application
import android.graphics.Bitmap
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.AppDatabase
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.BottleInfoDao
import com.rite.pillcounting.core.room.dao.DrugMasterDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDetailsDao
import com.rite.pillcounting.core.room.dao.StockTxnDao
import com.rite.pillcounting.core.room.dao.UserDao
import com.rite.pillcounting.core.room.models.PillCountTxnDetailsEntity
import com.rite.pillcounting.core.scanning.data.DrugImageDownloader
import com.rite.pillcounting.core.scanning.domain.data.IDrugRepository
import com.rite.pillcounting.core.scanning.logic.CameraHelper
import com.rite.pillcounting.core.scanning.logic.PillDetectionModelLoader
import com.rite.pillcounting.core.utils.common.BarcodeDecoder
import com.rite.pillcounting.core.utils.common.LocationProvider
import com.rite.pillcounting.core.utils.common.SoundUtils
import com.rite.pillcounting.core.utils.logger.PerformanceLogger
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.hl7.data.repository.Hl7Repository
import com.rite.pillcounting.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Regression tests for the single-tap contract on the VIAL photo capture.
 *
 * A capture's bitmap arrives asynchronously, so these tests hold the
 * CameraHelper callback in a MockK slot and invoke it by hand — that is what
 * lets a test sit inside the "capture in flight" window and tap again, which is
 * exactly the window the multi-tap bug lived in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PillScanningViewModelCaptureTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val application: Application = mockk(relaxed = true)
    private val preferenceHelper: PreferenceHelper = mockk(relaxed = true)
    private val pillCountTxnDao: PillCountTxnDao = mockk(relaxed = true)
    private val stockTxnDao: StockTxnDao = mockk(relaxed = true)
    private val bottleInfoDao: BottleInfoDao = mockk(relaxed = true)
    private val userDao: UserDao = mockk(relaxed = true)
    private val pillCountTxnDetailsDao: PillCountTxnDetailsDao = mockk(relaxed = true)
    private val locationProvider: LocationProvider = mockk(relaxed = true)
    private val drugMasterDao: DrugMasterDao = mockk(relaxed = true)
    private val modelLoader: PillDetectionModelLoader = mockk(relaxed = true)
    private val performanceLogger: PerformanceLogger = mockk(relaxed = true)
    private val barcodeDecoder: BarcodeDecoder = mockk(relaxed = true)
    private val drugRepository: IDrugRepository = mockk(relaxed = true)
    private val drugImageDownloader: DrugImageDownloader = mockk(relaxed = true)
    private val hl7Repository: Hl7Repository = mockk(relaxed = true)
    private val batchDao: BatchDao = mockk(relaxed = true)
    private val appDatabase: AppDatabase = mockk(relaxed = true)

    private val cameraHelper: CameraHelper = mockk(relaxed = true)

    private lateinit var viewModel: PillScanningViewModel

    @Before
    fun setup() {
        every { application.applicationContext } returns application
        every { application.getString(any<Int>()) } returns "test error"
        every { application.getString(any<Int>(), any()) } returns "test error"
        every { pillCountTxnDetailsDao.observeAllForTxn(any(), any()) } returns flowOf(emptyList())
        coEvery { pillCountTxnDetailsDao.getLatestType(any()) } returns null
        coJustRun { performanceLogger.logPerformanceSnapshot(any()) }
        every { preferenceHelper.getControlDrugTypes() } returns emptySet()
        every { preferenceHelper.isRequireDoubleCountEnabled() } returns false
        every { preferenceHelper.isRequireBackCountEnabled() } returns false

        // playCaptureSound reads the AudioManager off the Context; a relaxed
        // Application mock cannot serve that, so stub the object out.
        mockkObject(SoundUtils)
        every { SoundUtils.playCaptureSound(any()) } returns Unit

        viewModel = PillScanningViewModel(
            app = application,
            preferenceHelper = preferenceHelper,
            pillCountTxnDao = pillCountTxnDao,
            stockTxnDao = stockTxnDao,
            bottleInfoDao = bottleInfoDao,
            batchDao = batchDao,
            userDao = userDao,
            pillCountTxnDetailsDao = pillCountTxnDetailsDao,
            locationProvider = locationProvider,
            drugMasterDao = drugMasterDao,
            modelLoader = modelLoader,
            performanceLogger = performanceLogger,
            barcodeDecoder = barcodeDecoder,
            drugRepository = drugRepository,
            drugImageDownloader = drugImageDownloader,
            hl7Repository = hl7Repository,
            appDatabase = appDatabase,
        )
        viewModel.attachCameraHelper(cameraHelper)
        setCurrentStep(StepState.VIAL)
    }

    @After
    fun tearDown() { unmockkAll() }

    /** `_currentStep` has no setter; the sibling VM tests reach it the same way. */
    private fun setCurrentStep(step: StepState) {
        val field = PillScanningViewModel::class.java.getDeclaredField("_currentStep")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(viewModel) as MutableStateFlow<StepState>
        flow.value = step
    }

    // ─────────────────────────── capture button ───────────────────────────

    @Test
    fun `captureImage ignores a second tap while the first capture is still in flight`() = runTest {
        every { cameraHelper.captureImage(any(), any()) } returns true

        viewModel.captureImage()
        viewModel.captureImage()
        viewModel.captureImage()

        // Only the first tap reaches the camera; the rest are inert.
        verify(exactly = 1) { cameraHelper.captureImage(any(), any()) }
        verify(exactly = 1) { SoundUtils.playCaptureSound(any()) }
        assertTrue(viewModel.isCapturing.value)
    }

    @Test
    fun `captureImage accepts a new tap once the previous capture delivers its bitmap`() = runTest {
        val onCaptured = slot<(Bitmap) -> Unit>()
        every { cameraHelper.captureImage(capture(onCaptured), any()) } returns true
        val bitmap: Bitmap = mockk(relaxed = true)

        viewModel.captureImage()
        onCaptured.captured.invoke(bitmap)
        advanceUntilIdle()

        assertFalse(viewModel.isCapturing.value)
        assertSame(bitmap, viewModel.capturedBitmap.value)

        viewModel.captureImage()
        verify(exactly = 2) { cameraHelper.captureImage(any(), any()) }
    }

    @Test
    fun `captureImage clears isCapturing when the capture reports an error`() = runTest {
        val onCaptureError = slot<(Throwable) -> Unit>()
        every { cameraHelper.captureImage(any(), capture(onCaptureError)) } returns true

        viewModel.captureImage()
        onCaptureError.captured.invoke(RuntimeException("capture failed"))
        advanceUntilIdle()

        // A failed capture must not leave the shutter permanently disabled.
        assertFalse(viewModel.isCapturing.value)
        assertNull(viewModel.capturedBitmap.value)

        viewModel.captureImage()
        verify(exactly = 2) { cameraHelper.captureImage(any(), any()) }
    }

    @Test
    fun `captureImage clears isCapturing when the camera refuses the request`() = runTest {
        every { cameraHelper.captureImage(any(), any()) } returns false

        viewModel.captureImage()

        assertFalse(viewModel.isCapturing.value)
    }

    @Test
    fun `a bitmap arriving after the VIAL step ended is dropped`() = runTest {
        val onCaptured = slot<(Bitmap) -> Unit>()
        every { cameraHelper.captureImage(capture(onCaptured), any()) } returns true
        val bitmap: Bitmap = mockk(relaxed = true)

        viewModel.captureImage()
        // The user finished the vial step before this capture came back.
        setCurrentStep(StepState.CONTAINER_PENDING)
        onCaptured.captured.invoke(bitmap)
        advanceUntilIdle()

        // Repopulating capturedBitmap here would re-show the still overlay on
        // top of the next step with no way back to the live camera.
        assertNull(viewModel.capturedBitmap.value)
        assertFalse(viewModel.isCapturing.value)
    }

    @Test
    fun `rapid taps on the auto-confirm path write the vial photo exactly once`() = runTest {
        val onCaptured = slot<(Bitmap) -> Unit>()
        every { cameraHelper.captureImage(capture(onCaptured), any()) } returns true
        every { preferenceHelper.getTxnId() } returns 42L
        coEvery { pillCountTxnDetailsDao.getTotalPillCountForTxn(any()) } returns 0
        val bitmap: Bitmap = mockk(relaxed = true)
        every { bitmap.isRecycled } returns true // skip the real file write

        viewModel.captureImage(autoConfirm = true)
        viewModel.captureImage(autoConfirm = true)
        viewModel.captureImage(autoConfirm = true)
        onCaptured.captured.invoke(bitmap)
        advanceUntilIdle()

        // Three taps, one commit: one delete+insert pair, not three.
        coVerify(exactly = 1) { pillCountTxnDetailsDao.deleteVialByTxnId(42L, StepState.VIAL) }
        coVerify(exactly = 1) { pillCountTxnDetailsDao.insert(any<PillCountTxnDetailsEntity>()) }
    }

    // ─────────────────────────── done button ───────────────────────────

    @Test
    fun `saveCaptureImage commits once even when Done is tapped twice`() = runTest {
        val onCaptured = slot<(Bitmap) -> Unit>()
        every { cameraHelper.captureImage(capture(onCaptured), any()) } returns true
        every { preferenceHelper.getTxnId() } returns 42L
        coEvery { pillCountTxnDetailsDao.getTotalPillCountForTxn(any()) } returns 0
        val bitmap: Bitmap = mockk(relaxed = true)
        every { bitmap.isRecycled } returns true

        viewModel.captureImage()
        onCaptured.captured.invoke(bitmap)
        viewModel.saveCaptureImage()
        viewModel.saveCaptureImage()
        advanceUntilIdle()

        coVerify(exactly = 1) { pillCountTxnDetailsDao.insert(any<PillCountTxnDetailsEntity>()) }
    }

    @Test
    fun `redoCaptureImage re-arms Done for the next still`() = runTest {
        val onCaptured = slot<(Bitmap) -> Unit>()
        every { cameraHelper.captureImage(capture(onCaptured), any()) } returns true
        every { preferenceHelper.getTxnId() } returns 42L
        coEvery { pillCountTxnDetailsDao.getTotalPillCountForTxn(any()) } returns 0
        val bitmap: Bitmap = mockk(relaxed = true)
        every { bitmap.isRecycled } returns true

        viewModel.captureImage()
        onCaptured.captured.invoke(bitmap)
        viewModel.saveCaptureImage()
        advanceUntilIdle()

        // Redo → recapture → Done must still commit.
        viewModel.redoCaptureImage()
        setCurrentStep(StepState.VIAL)
        viewModel.captureImage()
        onCaptured.captured.invoke(bitmap)
        viewModel.saveCaptureImage()
        advanceUntilIdle()

        coVerify(exactly = 2) { pillCountTxnDetailsDao.insert(any<PillCountTxnDetailsEntity>()) }
    }
}
