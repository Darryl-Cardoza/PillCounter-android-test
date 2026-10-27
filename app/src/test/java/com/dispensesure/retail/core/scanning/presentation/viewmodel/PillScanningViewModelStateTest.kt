package com.dispensesure.retail.core.scanning.presentation.viewmodel

import android.app.Application
import com.dispensesure.retail.core.room.dao.StockBatchDao
import com.dispensesure.retail.core.room.dao.StockBottleInfoDao
import com.dispensesure.retail.core.room.dao.DrugMasterDao
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.core.room.dao.DispenseCountDao
import com.dispensesure.retail.core.room.dao.StockTxnDao
import com.dispensesure.retail.core.faceAuth.data.OperatorNameProvider
import com.dispensesure.retail.core.room.dao.UserDao
import com.dispensesure.retail.core.room.AppDatabase
import com.dispensesure.retail.core.scanning.engine.TestEngineFactory
import com.dispensesure.retail.feature.hl7.data.repository.Hl7Repository
import com.dispensesure.retail.core.scanning.logic.PillDetectionModelLoader
import com.dispensesure.retail.core.scanning.logic.ScanDecoder
import com.dispensesure.retail.core.utils.common.BarcodeDecoder
import com.dispensesure.retail.core.utils.common.LocationProvider
import com.dispensesure.retail.core.utils.logger.PerformanceLogger
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.util.MainDispatcherRule
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [PillScanningViewModel] simple state-mutation methods.
 *
 * Each test calls a single public method and verifies the resulting StateFlow value.
 * No Turbine subscription is needed here because these methods update MutableStateFlow
 * fields directly (not via SharingStarted.WhileSubscribed upstream).
 *
 * The AndroidViewModel requires a mocked Application whose applicationContext and
 * getString() are stubbed so that any internal context.getString(R.string.xxx) calls
 * during init or coroutines return "test error" instead of crashing on the JVM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PillScanningViewModelStateTest {

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
        // observeAllForTxn is called from observeTxnDetailsForTxn (triggered via getDrugInfo)
        every { dispenseCountDao.observeAllForTxn(any(), any()) } returns flowOf(emptyList())
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

    // ─────────────────────────── Dialog / error state ───────────────────────────

    // SCAN_VM_001
    @Test
    fun `clearErrorMessage sets showErrorMessage to null in uiState`() = runTest {
        viewModel.clearErrorMessage()

        assertNull(viewModel.uiState.value.showErrorMessage)
    }

    // SCAN_VM_002
    @Test
    fun `showEndStockCountDialog sets showEndStockCountDialog to true`() = runTest {
        viewModel.showEndStockCountDialog()

        assertTrue(viewModel.uiState.value.showEndStockCountDialog)
    }

    // SCAN_VM_003
    @Test
    fun `handleDismissDialog clears showCountMismatchDialog and showEndStockCountDialog`() = runTest {
        viewModel.showEndStockCountDialog() // sets showEndStockCountDialog = true

        viewModel.handleDismissDialog()

        assertFalse(viewModel.uiState.value.showCountMismatchDialog)
        assertFalse(viewModel.uiState.value.showEndStockCountDialog)
        assertFalse(viewModel.uiState.value.showDialogForControl)
    }

    // ─────────────────────────── restrictAdd / noTransaction ───────────────────────────

    // SCAN_VM_004
    @Test
    fun `resetRestrictAdd sets restrictAdd to false in uiState`() = runTest {
        viewModel.resetRestrictAdd()

        assertFalse(viewModel.uiState.value.restrictAdd)
    }

    // SCAN_VM_005
    @Test
    fun `resetNoTransaction sets showNoTransaction to false in uiState`() = runTest {
        viewModel.resetNoTransaction()

        assertFalse(viewModel.uiState.value.showNoTransaction)
    }

    // ─────────────────────────── Dialog visibility toggles ───────────────────────────

    // SCAN_VM_006
    @Test
    fun `setTargetCountDialogShown(true) sets showTargetCountDialog to true`() = runTest {
        viewModel.setTargetCountDialogShown(true)

        assertTrue(viewModel.uiState.value.showTargetCountDialog)
    }

    // SCAN_VM_007
    @Test
    fun `setTargetCountDialogShown(false) clears showTargetCountDialog`() = runTest {
        viewModel.setTargetCountDialogShown(true)

        viewModel.setTargetCountDialogShown(false)

        assertFalse(viewModel.uiState.value.showTargetCountDialog)
    }

    // SCAN_VM_008
    @Test
    fun `setNoteDialogShown(true) sets showNotesDialog to true`() = runTest {
        viewModel.setNoteDialogShown(true)

        assertTrue(viewModel.uiState.value.showNotesDialog)
    }

    // ─────────────────────────── Scan type / target count ───────────────────────────

    // SCAN_VM_009
    @Test
    fun `setScanType stores the type string in uiState scanType field`() = runTest {
        viewModel.setScanType("FIXED")

        assertEquals("FIXED", viewModel.uiState.value.scanType)
    }

    // SCAN_VM_010
    @Test
    fun `updateTargetCount stores value in uiState and persists via DAO`() = runTest {
        viewModel.updateTargetCount(42)
        advanceUntilIdle()

        assertEquals(42, viewModel.uiState.value.targetCount)
        coVerify { dispenseTxnDao.updateTargetCount(any(), eq(42), any()) }
    }

    // ─────────────────────────── Camera pause control ───────────────────────────

    // SCAN_VM_011
    @Test
    fun `setCameraPaused(true) sets cameraPaused flow to true and clears detectedPills`() = runTest {
        viewModel.setCameraPaused(true)
        runCurrent()

        assertTrue(viewModel.cameraPaused.value)
        assertTrue(viewModel.uiState.value.detectedPills.isEmpty())
    }

    // SCAN_VM_012
    @Test
    fun `setCameraPaused(false) sets cameraPaused flow to false`() = runTest {
        viewModel.setCameraPaused(true)
        runCurrent()
        assertTrue(viewModel.cameraPaused.value)

        viewModel.setCameraPaused(false)
        runCurrent()

        assertFalse(viewModel.cameraPaused.value)
    }

    // ─────────────────────────── Glove detection ───────────────────────────

    // SCAN_VM_013
    @Test
    fun `resetGloveDetection sets glovesDetected to false`() = runTest {
        viewModel.resetGloveDetection()

        assertFalse(viewModel.glovesDetected.value)
    }
}
