package com.rite.pillcounting.core.scanning.presentation.viewmodel

import android.app.Application
import com.rite.pillcounting.core.room.dao.DrugMasterDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDetailsDao
import com.rite.pillcounting.core.room.dao.UserDao
import com.rite.pillcounting.core.room.models.DrugMasterEntity
import com.rite.pillcounting.core.room.models.PillCountTxnEntity
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.scanning.domain.data.IDrugRepository
import com.rite.pillcounting.core.scanning.domain.data.PillScanningEvent
import com.rite.pillcounting.core.scanning.logic.PillDetectionModelLoader
import com.rite.pillcounting.core.utils.common.BarcodeDecoder
import com.rite.pillcounting.core.utils.common.LocationProvider
import com.rite.pillcounting.core.utils.logger.PerformanceLogger
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [PillScanningViewModel] NDC-scan flow and handleConfirmDone.
 *
 * NDC-scan tests require the scan-mode precondition: [enterStockCountScanMode] +
 * [getDrugInfo] + advanceUntilIdle sets currentStep to StepState.SCAN and
 * forceStartOnScan=true, enabling the NDC-scan guard in onNdcScannedForStockCount.
 *
 * handleConfirmDone is triggered via onEvent(PillScanningEvent.ConfirmDone) and
 * verified through DAO call assertions. Both updateTxnStatus and
 * markCompletedAndUnsynced have a default timestamp param — use any() in coVerify.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PillScanningViewModelNdcScanTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val application: Application = mockk(relaxed = true)
    private val preferenceHelper: PreferenceHelper = mockk(relaxed = true)
    private val pillCountTxnDao: PillCountTxnDao = mockk(relaxed = true)
    private val userDao: UserDao = mockk(relaxed = true)
    private val pillCountTxnDetailsDao: PillCountTxnDetailsDao = mockk(relaxed = true)
    private val locationProvider: LocationProvider = mockk(relaxed = true)
    private val drugMasterDao: DrugMasterDao = mockk(relaxed = true)
    private val modelLoader: PillDetectionModelLoader = mockk(relaxed = true)
    private val performanceLogger: PerformanceLogger = mockk(relaxed = true)
    private val barcodeDecoder: BarcodeDecoder = mockk(relaxed = true)
    private val drugRepository: IDrugRepository = mockk(relaxed = true)

    private lateinit var viewModel: PillScanningViewModel

    companion object {
        private const val VALID_GTIN = "00000000000000" // 14-digit all-numeric
        private const val BATCH_ID = 1L
    }

    @Before
    fun setup() {
        every { application.applicationContext } returns application
        every { application.getString(any<Int>()) } returns "test error"
        every { application.getString(any<Int>(), any()) } returns "test error"
        every { pillCountTxnDetailsDao.observeAllForTxn(any(), any()) } returns flowOf(emptyList())
        coEvery { pillCountTxnDetailsDao.getLatestType(any()) } returns null
        coJustRun { performanceLogger.logPerformanceSnapshot(any()) }
        viewModel = PillScanningViewModel(
            app = application,
            preferenceHelper = preferenceHelper,
            pillCountTxnDao = pillCountTxnDao,
            userDao = userDao,
            pillCountTxnDetailsDao = pillCountTxnDetailsDao,
            locationProvider = locationProvider,
            drugMasterDao = drugMasterDao,
            modelLoader = modelLoader,
            performanceLogger = performanceLogger,
            barcodeDecoder = barcodeDecoder,
            drugRepository = drugRepository,
        )
    }

    @After
    fun tearDown() { unmockkAll() }

    // ─────────────────────────── onNdcScannedForStockCount ───────────────────────────

    // SCAN_VM_019
    @Test
    fun `onNdcScannedForStockCount is a no-op when forceStartOnScan is false`() = runTest {
        // forceStartOnScan = false by default — method returns on the first guard
        assertNull(viewModel.uiState.value.showErrorMessage)

        viewModel.onNdcScannedForStockCount("ANYTHING")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.showErrorMessage)
        coVerify(exactly = 0) { drugMasterDao.getDrugByGtin(any()) }
    }

    // SCAN_VM_020
    @Test
    fun `onNdcScannedForStockCount sets showErrorMessage when gtin14 is invalid`() = runTest {
        // Arm scan mode: enterStockCountScanMode + getDrugInfo → currentStep=SCAN
        viewModel.enterStockCountScanMode(batchId = BATCH_ID)
        viewModel.getDrugInfo()
        advanceUntilIdle()
        // Relaxed barcodeDecoder: toGtin14 returns "" → blank → fails the length check

        viewModel.onNdcScannedForStockCount("INVALID_LABEL")
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.showErrorMessage)
    }

    // SCAN_VM_021
    @Test
    fun `onNdcScannedForStockCount saves txnId when drug is found locally`() = runTest {
        viewModel.enterStockCountScanMode(batchId = BATCH_ID)
        viewModel.getDrugInfo()
        advanceUntilIdle()

        val drug = DrugMasterEntity(drugId = 7L, ndc = VALID_GTIN)
        every { barcodeDecoder.isGs1Barcode(VALID_GTIN) } returns false
        every { barcodeDecoder.toGtin14(VALID_GTIN) } returns VALID_GTIN
        coEvery { drugMasterDao.getDrugByGtin(VALID_GTIN) } returns drug
        coEvery { pillCountTxnDao.findSealedTxnInBatch(any(), any(), any(), any()) } returns null
        coEvery { pillCountTxnDao.upsertPreservingId(any()) } returns 9L
        coEvery { pillCountTxnDao.getTxnWithDetails(9L) } returns null

        viewModel.onNdcScannedForStockCount(VALID_GTIN)
        advanceUntilIdle()

        verify { preferenceHelper.saveTxnId(9L) }
    }

    // SCAN_VM_022
    @Test
    fun `onNdcScannedForStockCount sets showErrorMessage when drug is not found locally or via API`() = runTest {
        viewModel.enterStockCountScanMode(batchId = BATCH_ID)
        viewModel.getDrugInfo()
        advanceUntilIdle()

        every { barcodeDecoder.isGs1Barcode(VALID_GTIN) } returns false
        every { barcodeDecoder.toGtin14(VALID_GTIN) } returns VALID_GTIN
        coEvery { drugMasterDao.getDrugByGtin(VALID_GTIN) } returns null
        coEvery { drugMasterDao.getDrugByNdc(VALID_GTIN) } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any()) } returns null

        viewModel.onNdcScannedForStockCount(VALID_GTIN)
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.showErrorMessage)
    }

    // ─────────────────────────── handleConfirmDone (via PillScanningEvent.ConfirmDone) ───────────────────────────

    // SCAN_VM_023
    @Test
    fun `ConfirmDone with total equal to zero skips DAO completion and resets showConfirmDialog`() = runTest {
        every { preferenceHelper.getTxnId() } returns 1L
        coEvery { pillCountTxnDetailsDao.getTotalPillCountForTxn(1L) } returns 0
        coEvery { pillCountTxnDao.getById(1L) } returns PillCountTxnEntity(
            txnId = 1L, countType = CountType.FIXED, status = CountStatus.PARTIAL
        )

        viewModel.onEvent(PillScanningEvent.ConfirmDone)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showConfirmDialog)
        coVerify(exactly = 0) { pillCountTxnDao.updateTxnStatus(any(), any(), any()) }
        coVerify(exactly = 0) { pillCountTxnDao.markCompletedAndUnsynced(any(), any(), any()) }
    }

    // SCAN_VM_024
    @Test
    fun `ConfirmDone with total at least target calls updateTxnStatus COMPLETED for non-HL7`() = runTest {
        every { preferenceHelper.getTxnId() } returns 1L
        coEvery { pillCountTxnDetailsDao.getTotalPillCountForTxn(1L) } returns 10
        coEvery { pillCountTxnDao.getById(1L) } returns PillCountTxnEntity(
            txnId = 1L,
            countType = CountType.FIXED,
            status = CountStatus.PARTIAL,
            targetCount = 10,
            isComingFromHL7 = false
        )

        viewModel.onEvent(PillScanningEvent.ConfirmDone)
        advanceUntilIdle()

        coVerify(exactly = 1) { pillCountTxnDao.updateTxnStatus(eq(1L), eq(CountStatus.COMPLETED), any()) }
        coVerify(exactly = 0) { pillCountTxnDao.markCompletedAndUnsynced(any(), any(), any()) }
    }

    // SCAN_VM_025
    @Test
    fun `ConfirmDone for HL7 transaction calls markCompletedAndUnsynced instead of updateTxnStatus`() = runTest {
        every { preferenceHelper.getTxnId() } returns 2L
        coEvery { pillCountTxnDetailsDao.getTotalPillCountForTxn(2L) } returns 5
        coEvery { pillCountTxnDao.getById(2L) } returns PillCountTxnEntity(
            txnId = 2L,
            countType = CountType.FIXED,
            status = CountStatus.PARTIAL,
            targetCount = 10,   // 5 < 10 → status is PARTIAL
            isComingFromHL7 = true
        )

        viewModel.onEvent(PillScanningEvent.ConfirmDone)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            pillCountTxnDao.markCompletedAndUnsynced(
                txnId = eq(2L), status = eq(CountStatus.PARTIAL), now = any()
            )
        }
        coVerify(exactly = 0) { pillCountTxnDao.updateTxnStatus(any(), any(), any()) }
    }
}
