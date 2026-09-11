package com.rite.pillcounting.core.scanning.presentation.viewmodel

import android.app.Application
import androidx.camera.core.ImageProxy
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.BottleInfoDao
import com.rite.pillcounting.core.room.dao.DrugMasterDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDetailsDao
import com.rite.pillcounting.core.room.dao.StockTxnDao
import com.rite.pillcounting.core.room.dao.UserDao
import com.rite.pillcounting.core.room.AppDatabase
import com.rite.pillcounting.core.scanning.data.DrugImageDownloader
import com.rite.pillcounting.feature.hl7.data.repository.Hl7Repository
import com.rite.pillcounting.core.scanning.domain.data.IDrugRepository
import com.rite.pillcounting.core.scanning.domain.data.PillScanningEvent
import com.rite.pillcounting.core.scanning.logic.PillDetectionModelLoader
import com.rite.pillcounting.core.scanning.presentation.viewmodel.PillScanningViewModel.Companion.COUNT_CONFIRM_FRAMES
import com.rite.pillcounting.core.scanning.presentation.viewmodel.PillScanningViewModel.Companion.IDLE_STEP_MS
import com.rite.pillcounting.core.scanning.presentation.viewmodel.PillScanningViewModel.Companion.IDLE_TIMEOUT_MS
import com.rite.pillcounting.core.utils.common.BarcodeDecoder
import com.rite.pillcounting.core.utils.common.LocationProvider
import com.rite.pillcounting.core.utils.logger.PerformanceLogger
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [PillScanningViewModel] frame-capture routing and [getDrugInfo]
 * step resolution.
 *
 * onFrameCaptured tests are limited to the short-circuit paths that don't require
 * the ML model to actually run: isPaused=true and model not in ModelState.Ready.
 * In both cases the ImageProxy must be closed immediately.
 *
 * getDrugInfo tests verify that the correct StepState is resolved from the DAO
 * state and any explicit forceStartStep override.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PillScanningViewModelFrameTest {

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

    private lateinit var viewModel: PillScanningViewModel

    @Before
    fun setup() {
        every { application.applicationContext } returns application
        every { application.getString(any<Int>()) } returns "test error"
        every { application.getString(any<Int>(), any()) } returns "test error"
        every { pillCountTxnDetailsDao.observeAllForTxn(any(), any()) } returns flowOf(emptyList())
        // getLatestType returns StepState? — relaxed mock returns a child mock instead of null,
        // which would make getDrugInfo resolve to the wrong step. Force null explicitly.
        coEvery { pillCountTxnDetailsDao.getLatestType(any()) } returns null
        coJustRun { performanceLogger.logPerformanceSnapshot(any()) }
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
    }

    @After
    fun tearDown() { unmockkAll() }

    // ─────────────────────────── onFrameCaptured short-circuit paths ───────────────────────────

    // SCAN_VM_014
    @Test
    fun `onFrameCaptured closes image immediately when model is not in Ready state`() = runTest {
        // Model starts as ModelState.Idle — never explicitly loaded in this test
        val image = mockk<ImageProxy>(relaxed = true)

        viewModel.onFrameCaptured(image)

        verify { image.close() }
    }

    // SCAN_VM_015
    @Test
    fun `onFrameCaptured closes image immediately when pill detection is paused`() = runTest {
        val image = mockk<ImageProxy>(relaxed = true)
        viewModel.pausePillDetection() // sets isPaused = true

        viewModel.onFrameCaptured(image)

        verify { image.close() }
    }

    // ─────────────────────────── getDrugInfo step resolution ───────────────────────────

    // SCAN_VM_017
    @Test
    fun `getDrugInfo with no txn resolves currentStep to TARGET_VERIFICATION`() = runTest {
        // Relaxed mocks: getTxnWithDetails → null, getLatestType → null.
        // drugInfo is null (no txn -> no drugId), so drugType.isNullOrEmpty() is true,
        // which falls back to TARGET_VERIFICATION (not CONTAINER_INITIATE, which is only
        // reached when drugType is a known non-blank, non-"null" value).
        viewModel.getDrugInfo()
        advanceUntilIdle()

        assertEquals(StepState.TARGET_VERIFICATION, viewModel.currentStep.value)
    }

    // SCAN_VM_018
    @Test
    fun `getDrugInfo with explicit forceStartStep resolves currentStep to that step`() = runTest {
        viewModel.getDrugInfo(forceStartStep = StepState.TARGET_VERIFICATION)
        advanceUntilIdle()

        assertEquals(StepState.TARGET_VERIFICATION, viewModel.currentStep.value)
    }

    // ─────────────────────────── idle watchdog ───────────────────────────
    // The overlay must only fire on a genuinely static scene. A changing pill
    // count is active counting and has to keep pushing the deadline forward.

    // SCAN_VM_039
    @Test
    fun `count activity inside the window keeps the idle overlay hidden past the timeout`() = runTest {
        advanceTimeBy(IDLE_TIMEOUT_MS / 2)
        viewModel.noteCountActivity()
        advanceTimeBy(IDLE_TIMEOUT_MS)

        assertFalse(viewModel.uiState.value.showIdleOverlay)
        assertFalse(viewModel.cameraPaused.value)
    }

    // SCAN_VM_040
    @Test
    fun `no count activity for a full window shows the idle overlay and pauses the camera`() = runTest {
        advanceTimeBy(IDLE_TIMEOUT_MS + 1_000)

        assertTrue(viewModel.uiState.value.showIdleOverlay)
        assertTrue(viewModel.cameraPaused.value)
    }

    // SCAN_VM_041
    @Test
    fun `pauseIdleTimer stops the overlay from ever firing`() = runTest {
        viewModel.pauseIdleTimer()
        advanceTimeBy(IDLE_TIMEOUT_MS * 3)

        assertFalse(viewModel.uiState.value.showIdleOverlay)
    }

    // SCAN_VM_042
    @Test
    fun `resetIdleOverlay re-arms the watchdog so it can fire again`() = runTest {
        advanceTimeBy(IDLE_TIMEOUT_MS + 1_000)
        assertTrue(viewModel.uiState.value.showIdleOverlay)

        viewModel.resetIdleOverlay()
        assertFalse(viewModel.uiState.value.showIdleOverlay)

        advanceTimeBy(IDLE_TIMEOUT_MS + 1_000)
        assertTrue(viewModel.uiState.value.showIdleOverlay)
    }

    // SCAN_VM_043
    @Test
    fun `add tap re-arms the watchdog`() = runTest {
        advanceTimeBy(IDLE_TIMEOUT_MS / 2)
        viewModel.onEvent(
            PillScanningEvent.AddTransactionDetailClicked(
                filteredCount = 5,
                stepType = StepState.TARGET_VERIFICATION,
            )
        )
        advanceTimeBy(IDLE_TIMEOUT_MS)

        assertFalse(viewModel.uiState.value.showIdleOverlay)
    }

    // SCAN_VM_044
    @Test
    fun `a single early activity tick does not delay the pause by a whole extra window`() = runTest {
        advanceTimeBy(3_000)
        viewModel.noteCountActivity()

        // Deadline is ~3s + timeout, not ~2x timeout.
        advanceTimeBy(IDLE_TIMEOUT_MS + IDLE_STEP_MS + 1_000)

        assertTrue(viewModel.uiState.value.showIdleOverlay)
    }

    // SCAN_VM_045
    @Test
    fun `count flicker that never holds still does not hold off the pause`() = runTest {
        // A still tray whose detector alternates 11 and 12 every frame.
        repeat(40) { i ->
            viewModel.onCountObserved(if (i % 2 == 0) 11 else 12)
            advanceTimeBy(2_000)
        }

        assertTrue(viewModel.uiState.value.showIdleOverlay)
    }

    // SCAN_VM_046
    @Test
    fun `a count held for the confirm window counts as activity`() = runTest {
        advanceTimeBy(IDLE_TIMEOUT_MS / 2)
        repeat(COUNT_CONFIRM_FRAMES) { viewModel.onCountObserved(11) }
        advanceTimeBy(IDLE_TIMEOUT_MS)

        assertFalse(viewModel.uiState.value.showIdleOverlay)
    }

    // SCAN_VM_047
    @Test
    fun `a confirmed count that then goes still still pauses`() = runTest {
        repeat(COUNT_CONFIRM_FRAMES) { viewModel.onCountObserved(11) }
        // Same count keeps arriving — confirmed once, so no further activity.
        repeat(20) { viewModel.onCountObserved(11) }
        advanceTimeBy(IDLE_TIMEOUT_MS + IDLE_STEP_MS + 1_000)

        assertTrue(viewModel.uiState.value.showIdleOverlay)
    }
}
