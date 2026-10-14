package com.dispensesure.retail.core.scanning.presentation.viewmodel

import android.app.Application
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.room.dao.StockBatchDao
import com.dispensesure.retail.core.room.dao.StockBottleInfoDao
import com.dispensesure.retail.core.room.dao.DrugMasterDao
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.core.room.dao.DispenseCountDao
import com.dispensesure.retail.core.room.dao.StockTxnDao
import com.dispensesure.retail.core.faceAuth.data.OperatorNameProvider
import com.dispensesure.retail.core.room.dao.UserDao
import com.dispensesure.retail.core.room.AppDatabase
import com.dispensesure.retail.core.scanning.engine.IdleWatchdog.Companion.IDLE_TIMEOUT_MS
import com.dispensesure.retail.core.scanning.engine.TestEngineFactory
import com.dispensesure.retail.feature.hl7.data.repository.Hl7Repository
import com.dispensesure.retail.core.scanning.logic.PillDetectionModelLoader
import com.dispensesure.retail.core.scanning.logic.ScanDecoder
import com.dispensesure.retail.core.utils.common.BarcodeDecoder
import com.dispensesure.retail.core.utils.common.LocationProvider
import com.dispensesure.retail.core.utils.logger.PerformanceLogger
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [PillScanningViewModel.getDrugInfo] step resolution, and that the
 * engine's idle pause reaches the flows the screen reads.
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
    private val dispenseTxnDao: DispenseTxnDao = mockk(relaxed = true)
    private val stockTxnDao: StockTxnDao = mockk(relaxed = true)
    private val stockBottleInfoDao: StockBottleInfoDao = mockk(relaxed = true)
    private val userDao: UserDao = mockk(relaxed = true)
    private val operatorNameProvider: OperatorNameProvider = mockk(relaxed = true)
    private val dispenseCountDao: DispenseCountDao = mockk(relaxed = true)
    private val locationProvider: LocationProvider = mockk(relaxed = true)
    private val drugMasterDao: DrugMasterDao = mockk(relaxed = true)
    private val modelLoader: PillDetectionModelLoader = mockk(relaxed = true)
    private val performanceLogger: PerformanceLogger = mockk(relaxed = true)
    private val barcodeDecoder: BarcodeDecoder = mockk(relaxed = true)
    private val hl7Repository: Hl7Repository = mockk(relaxed = true)
    private val stockBatchDao: StockBatchDao = mockk(relaxed = true)
    private val appDatabase: AppDatabase = mockk(relaxed = true)

    private lateinit var engineFactory: TestEngineFactory
    private lateinit var viewModel: PillScanningViewModel

    @Before
    fun setup() {
        every { application.applicationContext } returns application
        every { application.getString(any<Int>()) } returns "test error"
        every { application.getString(any<Int>(), any()) } returns "test error"
        every { dispenseCountDao.observeAllForTxn(any(), any()) } returns flowOf(emptyList())
        // getLatestStep returns StepState? — relaxed mock returns a child mock instead of null,
        // which would make getDrugInfo resolve to the wrong step. Force null explicitly.
        coEvery { dispenseCountDao.getLatestStep(any()) } returns null
        coJustRun { performanceLogger.logPerformanceSnapshot(any()) }
        engineFactory = TestEngineFactory(
            context = application,
            modelLoader = modelLoader,
            performanceLogger = performanceLogger,
            locationProvider = locationProvider,
            userDao = userDao,
            preferenceHelper = preferenceHelper,
        )
        viewModel = PillScanningViewModel(
            app = application,
            preferenceHelper = preferenceHelper,
            dispenseTxnDao = dispenseTxnDao,
            stockTxnDao = stockTxnDao,
            stockBottleInfoDao = stockBottleInfoDao,
            stockBatchDao = stockBatchDao,
            operatorNameProvider = operatorNameProvider,
            dispenseCountDao = dispenseCountDao,
            drugMasterDao = drugMasterDao,
            scanDecoder = ScanDecoder(barcodeDecoder),
            hl7Repository = hl7Repository,
            appDatabase = appDatabase,
            engineFactory = engineFactory,
        )
    }

    @After
    fun tearDown() { unmockkAll() }

    // ─────────────────────────── getDrugInfo step resolution ───────────────────────────

    // SCAN_VM_017
    @Test
    fun `getDrugInfo with no txn resolves currentStep to TARGET_VERIFICATION`() = runTest {
        // Relaxed mocks: getDispenseTxnDetail → null, getLatestStep → null.
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

    // ─────────────────────────── idle pause reaches the screen ───────────────────────────

    // SCAN_VM_040 (view model side; the watchdog itself is in PillCountingEngineTest)
    @Test
    fun `the engine's idle pause reaches uiState and cameraPaused`() = runTest {
        advanceTimeBy(IDLE_TIMEOUT_MS + 1_000)
        runCurrent()

        assertTrue(viewModel.uiState.value.showIdleOverlay)
        assertTrue(viewModel.cameraPaused.value)
    }
}
