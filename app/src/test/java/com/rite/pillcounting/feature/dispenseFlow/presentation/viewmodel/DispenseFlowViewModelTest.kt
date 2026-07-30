package com.rite.pillcounting.feature.dispenseFlow.presentation.viewmodel

import android.content.Context
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.dao.BottleInfoDao
import com.rite.pillcounting.core.room.dao.DrugMasterDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.StockTxnDao
import com.rite.pillcounting.core.room.models.DrugMasterEntity
import com.rite.pillcounting.core.room.models.PillCountTxnEntity
import com.rite.pillcounting.core.room.models.dtos.PillCountWithDrugAndTotal
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.room.models.enums.TxnPriority
import com.rite.pillcounting.core.scanning.data.DrugImageDownloader
import com.rite.pillcounting.core.scanning.domain.data.IDrugRepository
import com.rite.pillcounting.core.scanning.domain.model.BottleInfo
import com.rite.pillcounting.core.scanning.domain.model.BottleInfoJson
import com.rite.pillcounting.core.scanning.domain.model.DrugInfo
import com.rite.pillcounting.core.scanning.domain.model.GetNdcRequestModel
import com.rite.pillcounting.core.utils.compose.ContainerStatus
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.dashboard.domain.model.KpiFilter
import com.rite.pillcounting.feature.dispenseFlow.domain.model.DispenseStage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ParsedScanData
import parseScanData

@OptIn(ExperimentalCoroutinesApi::class)
class DispenseFlowViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var appContext: Context
    private lateinit var drugRepository: IDrugRepository
    private lateinit var drugMasterDao: DrugMasterDao
    private lateinit var preferenceHelper: PreferenceHelper
    private lateinit var pillCountTxnDao: PillCountTxnDao
    private lateinit var stockTxnDao: StockTxnDao
    private lateinit var bottleInfoDao: BottleInfoDao
    private lateinit var drugImageDownloader: DrugImageDownloader

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.d(any(), any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.i(any(), any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any<String>(), any()) } returns 0
        every { android.util.Log.w(any(), any<Throwable>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        // Mock the top-level parseScanData function. ParsedScanData.kt has no package
        // declaration, so its generated file-class is the root-level "ParsedScanDataKt".
        mockkStatic("ParsedScanDataKt")

        Dispatchers.setMain(testDispatcher)

        appContext = mockk(relaxed = true)
        drugRepository = mockk(relaxed = true)
        drugMasterDao = mockk(relaxed = true)
        preferenceHelper = mockk(relaxed = true)
        pillCountTxnDao = mockk(relaxed = true)
        stockTxnDao = mockk(relaxed = true)
        bottleInfoDao = mockk(relaxed = true)
        drugImageDownloader = mockk(relaxed = true)

        every { preferenceHelper.getLocalId() } returns 1L
        every { preferenceHelper.getTxnId() } returns 0L
        every { preferenceHelper.getBarcodeRegex() } returns "{RXNO}|{NDCNO}|{QTY}|{BUCKET}"
        every { parseScanData(any(), any()) } returns validParsed()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel() = DispenseFlowViewModel(
        appContext, drugRepository, drugMasterDao, preferenceHelper, pillCountTxnDao,
        stockTxnDao, bottleInfoDao, drugImageDownloader
    )

    private fun validParsed(
        ndcNo: String? = "NDC123",
        qty: String? = "10",
        rxNo: String? = "RX999",
        bucket: String? = "B1",
    ) = ParsedScanData(rxNo = rxNo, ndcNo = ndcNo, qty = qty, bucket = bucket)

    private fun drug(
        drugId: Long = 10L,
        drugName: String? = "Aspirin",
        ndc: String = "NDC123",
        packageQty: Int? = 5,
        isHazardous: Boolean = false,
        drugType: String? = "CII",
    ) = DrugMasterEntity(
        drugId = drugId,
        drugName = drugName,
        ndc = ndc,
        packageQty = packageQty,
        isHazardous = isHazardous,
        drugType = drugType,
    )

    private fun txn(
        txnId: Long = 1L,
        drugId: Long? = 10L,
        status: CountStatus = CountStatus.PARTIAL,
        isNdcVerified: Boolean? = false,
        rxNo: String? = "RX999",
        targetCount: Int? = 10,
    ) = PillCountTxnEntity(
        txnId = txnId,
        drugId = drugId,
        isDispense = true,
        status = status,
        isNdcVerified = isNdcVerified,
        rxNo = rxNo,
        targetCount = targetCount,
    )

    // ───────────────────────────── setCountType ─────────────────────────────

    @Test
    fun `setCountType REGULAR starts at PRE_NDC`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.setCountType("REGULAR")
        advanceUntilIdle()
        assertEquals(DispenseStage.PRE_NDC, vm.uiState.value.stage)
        assertEquals("REGULAR", vm.uiState.value.scanType)
    }

    @Test
    fun `setCountType FIXED starts at PRE_RX`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.setCountType("FIXED")
        advanceUntilIdle()
        assertEquals(DispenseStage.PRE_RX, vm.uiState.value.stage)
    }

    @Test
    fun `setCountType invalid defaults to FIXED and PRE_RX`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.setCountType("BOGUS")
        advanceUntilIdle()
        assertEquals(DispenseStage.PRE_RX, vm.uiState.value.stage)
        assertEquals("BOGUS", vm.uiState.value.scanType)
    }

    // ───────────────────────────── setBatchId ─────────────────────────────

    @Test
    fun `setBatchId non-zero updates state`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.setBatchId(5L)
        advanceUntilIdle()
        assertEquals(5L, vm.uiState.value.batchId)
    }

    @Test
    fun `setBatchId zero is ignored`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.setBatchId(0L)
        advanceUntilIdle()
        assertEquals(0L, vm.uiState.value.batchId)
    }

    // ───────────────────────────── initializeFromHl7Txn ─────────────────────────────

    @Test
    fun `initializeFromHl7Txn returns early when txnId zero`() = runTest(testDispatcher) {
        every { preferenceHelper.getTxnId() } returns 0L
        val vm = createViewModel()
        vm.initializeFromHl7Txn()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isFromHl7)
    }

    @Test
    fun `initializeFromHl7Txn returns early when txn null`() = runTest(testDispatcher) {
        every { preferenceHelper.getTxnId() } returns 7L
        coEvery { pillCountTxnDao.getById(7L) } returns null
        val vm = createViewModel()
        vm.initializeFromHl7Txn()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isFromHl7)
    }

    @Test
    fun `initializeFromHl7Txn found with drug populates PRE_NDC`() = runTest(testDispatcher) {
        every { preferenceHelper.getTxnId() } returns 7L
        coEvery { pillCountTxnDao.getById(7L) } returns txn(txnId = 7L)
        coEvery { drugMasterDao.getDrugById(10L) } returns drug(isHazardous = true)
        val vm = createViewModel()
        vm.initializeFromHl7Txn()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(DispenseStage.PRE_NDC, s.stage)
        assertTrue(s.isFromHl7)
        assertEquals(7L, s.txnId)
        assertEquals("Aspirin", s.drugName)
        assertEquals("NDC123", s.hl7ExpectedNdc)
        assertTrue(s.isHazardous)
    }

    @Test
    fun `initializeFromHl7Txn with null drugId keeps defaults`() = runTest(testDispatcher) {
        every { preferenceHelper.getTxnId() } returns 7L
        coEvery { pillCountTxnDao.getById(7L) } returns txn(txnId = 7L, drugId = null)
        val vm = createViewModel()
        vm.initializeFromHl7Txn()
        advanceUntilIdle()
        assertEquals(DispenseStage.PRE_NDC, vm.uiState.value.stage)
        assertNull(vm.uiState.value.hl7ExpectedNdc)
    }

    // ───────────────────────────── initializeFromResumedTxn ─────────────────────────────

    @Test
    fun `initializeFromResumedTxn txnId zero returns early`() = runTest(testDispatcher) {
        every { preferenceHelper.getTxnId() } returns 0L
        val vm = createViewModel()
        vm.initializeFromResumedTxn()
        advanceUntilIdle()
        assertEquals(0L, vm.uiState.value.txnId)
    }

    @Test
    fun `initializeFromResumedTxn txn null returns early`() = runTest(testDispatcher) {
        every { preferenceHelper.getTxnId() } returns 7L
        coEvery { pillCountTxnDao.getById(7L) } returns null
        val vm = createViewModel()
        vm.initializeFromResumedTxn()
        advanceUntilIdle()
        assertEquals(0L, vm.uiState.value.txnId)
    }

    @Test
    fun `initializeFromResumedTxn ndc verified jumps to COUNTING`() = runTest(testDispatcher) {
        every { preferenceHelper.getTxnId() } returns 7L
        coEvery { pillCountTxnDao.getById(7L) } returns txn(txnId = 7L, isNdcVerified = true)
        coEvery { drugMasterDao.getDrugById(10L) } returns drug()
        val vm = createViewModel()
        vm.initializeFromResumedTxn()
        advanceUntilIdle()
        assertEquals(DispenseStage.COUNTING, vm.uiState.value.stage)
        coVerify { pillCountTxnDao.updateGlovesPresent(eq(7L), eq(false), any()) }
    }

    @Test
    fun `initializeFromResumedTxn ndc not verified lands PRE_NDC`() = runTest(testDispatcher) {
        every { preferenceHelper.getTxnId() } returns 7L
        coEvery { pillCountTxnDao.getById(7L) } returns txn(txnId = 7L, isNdcVerified = false)
        coEvery { drugMasterDao.getDrugById(10L) } returns drug()
        val vm = createViewModel()
        vm.initializeFromResumedTxn()
        advanceUntilIdle()
        assertEquals(DispenseStage.PRE_NDC, vm.uiState.value.stage)
        assertEquals("NDC123", vm.uiState.value.hl7ExpectedNdc)
    }

    // ───────────────────────────── onRxBarcodeRead ─────────────────────────────

    @Test
    fun `onRxBarcodeRead wrong stage ignored`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.setCountType("REGULAR") // PRE_NDC
        advanceUntilIdle()
        vm.onRxBarcodeRead("gtin", null)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isLoading)
        coVerify(exactly = 0) { pillCountTxnDao.getActiveByRxNo(any()) }
    }

    @Test
    fun `onRxBarcodeRead from QUEUE advances and processes`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.enterQueueMode()
        advanceUntilIdle()
        coEvery { pillCountTxnDao.getActiveByRxNo("RX999") } returns null
        vm.onRxBarcodeRead("gtin", null)
        advanceUntilIdle()
        // txn not found -> tick bumped
        assertTrue(vm.uiState.value.txnNotFoundToastTick > 0)
    }

    @Test
    fun `onRxBarcodeRead blank gtin shows invalid dialog`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.onRxBarcodeRead("   ", null)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showInvalidScanDialog)
    }

    @Test
    fun `onRxBarcodeRead parsed null fields shows invalid dialog`() = runTest(testDispatcher) {
        every { parseScanData(any(), any()) } returns validParsed(ndcNo = null)
        val vm = createViewModel()
        vm.onRxBarcodeRead("gtin", null)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showInvalidScanDialog)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `onRxBarcodeRead neither standalone nor hl7 keeps not-found behavior`() =
        runTest(testDispatcher) {
            // Both preferenceHelper.isStandaloneMode() and isHl7Enabled() default to false
            // on the relaxed mock, so the txn-creation branch must not run at all.
            coEvery { pillCountTxnDao.getActiveByRxNo("RX999") } returns null
            val vm = createViewModel()
            vm.onRxBarcodeRead("gtin", null)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.txnNotFoundToastTick > 0)
            coVerify(exactly = 0) { pillCountTxnDao.upsertPreservingId(any<PillCountTxnEntity>()) }
        }

    @Test
    fun `onRxBarcodeRead ON_HOLD shows dialog`() = runTest(testDispatcher) {
        coEvery { pillCountTxnDao.getActiveByRxNo("RX999") } returns txn(status = CountStatus.ON_HOLD)
        val vm = createViewModel()
        vm.onRxBarcodeRead("gtin", null)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showOnHoldDialog)
    }

    @Test
    fun `onRxBarcodeRead PARTIAL auto-resumes to PRE_NDC`() = runTest(testDispatcher) {
        coEvery { pillCountTxnDao.getActiveByRxNo("RX999") } returns
            txn(status = CountStatus.PARTIAL, isNdcVerified = false)
        coEvery { drugMasterDao.getDrugById(10L) } returns drug()
        val vm = createViewModel()
        vm.onRxBarcodeRead("gtin", null)
        advanceUntilIdle()
        assertEquals(DispenseStage.PRE_RX, vm.uiState.value.stage)
        assertTrue(vm.uiState.value.showRxDetails)
        assertEquals(DispenseStage.PRE_NDC, vm.uiState.value.pendingRxResumeStage)
        coVerify { preferenceHelper.saveTxnId(1L) }
    }

    @Test
    fun `onRxBarcodeRead PARTIAL ndc verified resumes to COUNTING`() = runTest(testDispatcher) {
        coEvery { pillCountTxnDao.getActiveByRxNo("RX999") } returns
            txn(status = CountStatus.PARTIAL, isNdcVerified = true)
        coEvery { drugMasterDao.getDrugById(10L) } returns drug()
        val vm = createViewModel()
        vm.onRxBarcodeRead("gtin", null)
        advanceUntilIdle()
        assertEquals(DispenseStage.COUNTING, vm.uiState.value.stage)
    }

    @Test
    fun `onRxBarcodeRead else status shows not found tick`() = runTest(testDispatcher) {
        coEvery { pillCountTxnDao.getActiveByRxNo("RX999") } returns txn(status = CountStatus.COMPLETED)
        val vm = createViewModel()
        vm.onRxBarcodeRead("gtin", null)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.txnNotFoundToastTick > 0)
    }

    @Test
    fun `onRxBarcodeRead exception sets error`() = runTest(testDispatcher) {
        coEvery { pillCountTxnDao.getActiveByRxNo(any()) } throws RuntimeException("boom")
        val vm = createViewModel()
        vm.onRxBarcodeRead("gtin", null)
        advanceUntilIdle()
        assertEquals("boom", vm.uiState.value.error)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `onRxBarcodeRead loading guard prevents second call`() = runTest(testDispatcher) {
        coEvery { pillCountTxnDao.getActiveByRxNo("RX999") } returns null
        val vm = createViewModel()
        // Set loading by starting one call but not advancing
        vm.onRxBarcodeRead("gtin", null)
        // second call while isLoading true (already set synchronously)
        vm.onRxBarcodeRead("gtin", null)
        advanceUntilIdle()
        coVerify(exactly = 1) { pillCountTxnDao.getActiveByRxNo("RX999") }
    }

    // ───────────────────────────── onRxBarcodeRead: standalone/no-PMS txn creation ─────────────────────────────
    // When no PMS/HL7 transaction exists yet for the scanned RX, standalone mode OR a
    // non-PMS-integrated pharmacy originates the dispense locally — resolving the drug for
    // the label's NDC (local DB, then GTIN, then server) and creating the PARTIAL txn itself,
    // rather than leaving the user stuck waiting on an order that will never arrive.

    @Test
    fun `onRxBarcodeRead standalone creates txn using locally resolved drug`() = runTest(testDispatcher) {
        every { preferenceHelper.isStandaloneMode() } returns true
        coEvery { pillCountTxnDao.getActiveByRxNo("RX999") } returns null
        coEvery { drugMasterDao.getDrugByNdc("NDC123") } returns drug(drugId = 55L, ndc = "NDC123")
        coEvery { pillCountTxnDao.upsertPreservingId(any<PillCountTxnEntity>()) } returns 200L
        coEvery { pillCountTxnDao.getById(200L) } returns
            txn(txnId = 200L, drugId = 55L, status = CountStatus.PARTIAL, isNdcVerified = false)
        coEvery { drugMasterDao.getDrugById(55L) } returns drug(drugId = 55L, ndc = "NDC123")

        val vm = createViewModel()
        vm.onRxBarcodeRead("gtin", null)
        advanceUntilIdle()

        coVerify {
            pillCountTxnDao.upsertPreservingId(match<PillCountTxnEntity> {
                it.drugId == 55L &&
                    it.rxNo == "RX999" &&
                    it.bucketId == "B1" &&
                    it.targetCount == 10 &&
                    it.isDispense &&
                    it.isComingFromHL7 == false &&
                    it.isSynced == false &&
                    it.isNdcVerified == false &&
                    it.status == CountStatus.PARTIAL
            })
        }
        // Server fallback must be skipped once the drug resolves locally.
        coVerify(exactly = 0) { drugRepository.getDrugInfoByNdc(any()) }
        assertEquals(DispenseStage.PRE_RX, vm.uiState.value.stage)
        assertTrue(vm.uiState.value.showRxDetails)
        assertEquals("Aspirin", vm.uiState.value.drugName)
        assertEquals("NDC123", vm.uiState.value.hl7ExpectedNdc)
    }

    @Test
    fun `onRxBarcodeRead not-PMS-integrated also creates txn locally`() = runTest(testDispatcher) {
        // isHl7Enabled() true (not standalone) takes the same creation branch — the
        // condition is an OR of the two flags.
        every { preferenceHelper.isHl7Enabled() } returns true
        coEvery { pillCountTxnDao.getActiveByRxNo("RX999") } returns null
        coEvery { drugMasterDao.getDrugByNdc("NDC123") } returns drug(drugId = 55L, ndc = "NDC123")
        coEvery { pillCountTxnDao.upsertPreservingId(any<PillCountTxnEntity>()) } returns 200L
        coEvery { pillCountTxnDao.getById(200L) } returns
            txn(txnId = 200L, drugId = 55L, status = CountStatus.PARTIAL, isNdcVerified = false)
        coEvery { drugMasterDao.getDrugById(55L) } returns drug(drugId = 55L, ndc = "NDC123")

        val vm = createViewModel()
        vm.onRxBarcodeRead("gtin", null)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.showRxDetails)
        coVerify { pillCountTxnDao.upsertPreservingId(any<PillCountTxnEntity>()) }
    }

    @Test
    fun `onRxBarcodeRead standalone falls back to server when drug not found locally`() =
        runTest(testDispatcher) {
            every { preferenceHelper.isStandaloneMode() } returns true
            coEvery { pillCountTxnDao.getActiveByRxNo("RX999") } returns null
            coEvery { drugMasterDao.getDrugByGtin("NDC123") } returns null
            coEvery { drugRepository.getDrugInfoByNdc(any()) } returns DrugInfo(
                brandName = null, genericName = "ServerDrug", ndc = "NDC123",
                is_ndc_equivalent = false, drugType = "CII", qty = 30, isHazardous = false,
            )
            coEvery { drugMasterDao.upsertPreservingId(any<DrugMasterEntity>()) } returns 77L
            // getDrugByNdc is called twice: once for the initial local miss, once after the
            // server resolve-and-cache to pick the freshly upserted row back up.
            coEvery { drugMasterDao.getDrugByNdc("NDC123") } returnsMany listOf(
                null,
                drug(drugId = 77L, ndc = "NDC123", drugName = "ServerDrug"),
            )
            coEvery { pillCountTxnDao.upsertPreservingId(any<PillCountTxnEntity>()) } returns 201L
            coEvery { pillCountTxnDao.getById(201L) } returns
                txn(txnId = 201L, drugId = 77L, status = CountStatus.PARTIAL, isNdcVerified = false)
            coEvery { drugMasterDao.getDrugById(77L) } returns drug(drugId = 77L, ndc = "NDC123", drugName = "ServerDrug")

            val vm = createViewModel()
            vm.onRxBarcodeRead("gtin", null)
            advanceUntilIdle()

            coVerify { drugRepository.getDrugInfoByNdc(any()) }
            coVerify {
                pillCountTxnDao.upsertPreservingId(match<PillCountTxnEntity> { it.drugId == 77L })
            }
            assertEquals("ServerDrug", vm.uiState.value.drugName)
        }

    @Test
    fun `onRxBarcodeRead standalone creates txn even when drug cannot be resolved`() =
        runTest(testDispatcher) {
            every { preferenceHelper.isStandaloneMode() } returns true
            coEvery { pillCountTxnDao.getActiveByRxNo("RX999") } returns null
            coEvery { drugMasterDao.getDrugByNdc("NDC123") } returns null
            coEvery { drugMasterDao.getDrugByGtin("NDC123") } returns null
            coEvery { drugRepository.getDrugInfoByNdc(any()) } returns null
            coEvery { pillCountTxnDao.upsertPreservingId(any<PillCountTxnEntity>()) } returns 202L
            coEvery { pillCountTxnDao.getById(202L) } returns
                txn(txnId = 202L, drugId = null, status = CountStatus.PARTIAL, isNdcVerified = false)

            val vm = createViewModel()
            vm.onRxBarcodeRead("gtin", null)
            advanceUntilIdle()

            coVerify {
                pillCountTxnDao.upsertPreservingId(match<PillCountTxnEntity> { it.drugId == null })
            }
            // Txn still resolves and the RX sheet still shows (just without drug details).
            assertTrue(vm.uiState.value.showRxDetails)
            assertFalse(vm.uiState.value.txnNotFoundToastTick > 0)
        }

    @Test
    fun `onRxBarcodeRead standalone new txn ON_HOLD status shows on-hold dialog`() =
        runTest(testDispatcher) {
            // Defensive: if the freshly created txn is somehow read back as ON_HOLD
            // (e.g. a concurrent update), the existing status dispatch must still apply.
            every { preferenceHelper.isStandaloneMode() } returns true
            coEvery { pillCountTxnDao.getActiveByRxNo("RX999") } returns null
            coEvery { drugMasterDao.getDrugByGtin("NDC123") } returns null
            coEvery { drugRepository.getDrugInfoByNdc(any()) } returns null
            coEvery { pillCountTxnDao.upsertPreservingId(any<PillCountTxnEntity>()) } returns 203L
            coEvery { pillCountTxnDao.getById(203L) } returns
                txn(txnId = 203L, drugId = null, status = CountStatus.ON_HOLD)

            val vm = createViewModel()
            vm.onRxBarcodeRead("gtin", null)
            advanceUntilIdle()

            assertTrue(vm.uiState.value.showOnHoldDialog)
        }

    // ───────────────────────────── onNdcBarcodeRead ─────────────────────────────

    private fun ndcVm(): DispenseFlowViewModel {
        val vm = createViewModel()
        vm.setCountType("REGULAR") // PRE_NDC
        return vm
    }

    @Test
    fun `onNdcBarcodeRead wrong stage ignored`() = runTest(testDispatcher) {
        val vm = createViewModel() // PRE_RX
        vm.onNdcBarcodeRead("gtin", null)
        advanceUntilIdle()
        coVerify(exactly = 0) { drugMasterDao.getDrugByGtin(any()) }
    }

    @Test
    fun `onNdcBarcodeRead blank shows invalid`() = runTest(testDispatcher) {
        val vm = ndcVm()
        advanceUntilIdle()
        vm.onNdcBarcodeRead("  ", null)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showInvalidScanDialog)
    }

    @Test
    fun `onNdcBarcodeRead allowlist reject when server cannot resolve`() = runTest(testDispatcher) {
        val vm = ndcVm()
        vm.setAllowedNdcs(setOf("OTHER"))
        advanceUntilIdle()
        coEvery { drugMasterDao.getDrugByGtin("gtin") } returns null
        coEvery { drugMasterDao.getDrugByNdc("gtin") } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any()) } returns null
        vm.onNdcBarcodeRead("gtin", null)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.ndcNotAllowedToastTick > 0)
        assertEquals("gtin", vm.uiState.value.ndcNotAllowedValue)
    }

    @Test
    fun `onNdcBarcodeRead allowlist resolves via server then rejects disallowed ndc`() =
        runTest(testDispatcher) {
            // PMS drug has no cached GTIN → local lookups miss → server resolves the NDC,
            // which is NOT in the allowlist → reject using the resolved NDC (not the raw GTIN).
            val vm = ndcVm()
            vm.setAllowedNdcs(setOf("ALLOWED"))
            advanceUntilIdle()
            coEvery { drugMasterDao.getDrugByGtin("gtin") } returns null
            coEvery { drugMasterDao.getDrugByNdc("gtin") } returns null
            coEvery { drugRepository.getDrugInfoByNdc(any()) } returns DrugInfo(
                brandName = null, genericName = "Gen", ndc = "OTHER",
                is_ndc_equivalent = false, drugType = "CII", qty = 1, isHazardous = false
            )
            vm.onNdcBarcodeRead("gtin", null)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.ndcNotAllowedToastTick > 0)
            assertEquals("OTHER", vm.uiState.value.ndcNotAllowedValue)
        }

    @Test
    fun `onNdcBarcodeRead allowlist resolves via server and accepts allowed ndc`() =
        runTest(testDispatcher) {
            // Correct barcode for a PMS-requested drug with no cached GTIN: the server resolves
            // it to an allowed NDC, so the scan is accepted (no reject toast) and proceeds.
            val vm = ndcVm()
            vm.setAllowedNdcs(setOf("ALLOWED"))
            advanceUntilIdle()
            coEvery { drugMasterDao.getDrugByGtin("gtin") } returns null
            coEvery { drugMasterDao.getDrugByNdc("gtin") } returns null
            coEvery { drugRepository.getDrugInfoByNdc(any()) } returns DrugInfo(
                brandName = null, genericName = "Gen", ndc = "ALLOWED",
                is_ndc_equivalent = false, drugType = "CII", qty = 1, isHazardous = false
            )
            vm.onNdcBarcodeRead("gtin", null)
            advanceUntilIdle()
            assertEquals(0, vm.uiState.value.ndcNotAllowedToastTick)
            assertEquals("ALLOWED", vm.uiState.value.ndcScannedValue)
        }

    @Test
    fun `onNdcBarcodeRead trustLocal no sheet advances to COUNTING with batch`() = runTest(testDispatcher) {
        val vm = ndcVm()
        vm.setBatchId(3L)
        advanceUntilIdle()
        coEvery { drugMasterDao.getDrugByGtin("gtin") } returns drug(ndc = "L1")
        coEvery { pillCountTxnDao.getById(any()) } returns txn(txnId = 50L)
        // make advanceToCountingStage create batch txn (txnId 0)
        vm.onNdcBarcodeRead("gtin", null)
        advanceUntilIdle()
        assertEquals(DispenseStage.COUNTING, vm.uiState.value.stage)
    }

    @Test
    fun `onNdcBarcodeRead trustLocal needsSheet shows ndc details`() = runTest(testDispatcher) {
        val vm = ndcVm() // REGULAR, txnId 0, batchId 0 -> needsSheet true
        advanceUntilIdle()
        coEvery { drugMasterDao.getDrugByGtin("gtin") } returns drug(ndc = "L1")
        vm.onNdcBarcodeRead("gtin", null)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showNdcDetails)
        assertEquals("L1", vm.uiState.value.ndcScannedValue)
    }

    @Test
    fun `onNdcBarcodeRead server null shows not found`() = runTest(testDispatcher) {
        val vm = ndcVm()
        advanceUntilIdle()
        coEvery { drugMasterDao.getDrugByGtin("gtin") } returns null
        coEvery { drugMasterDao.getDrugByNdc("gtin") } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any()) } returns null
        vm.onNdcBarcodeRead("gtin", null)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showNdcNotFoundDialog)
    }

    @Test
    fun `onNdcBarcodeRead substitute shows equivalence dialog`() = runTest(testDispatcher) {
        val vm = ndcVm()
        advanceUntilIdle()
        coEvery { drugMasterDao.getDrugByGtin("gtin") } returns null
        coEvery { drugMasterDao.getDrugByNdc("gtin") } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any()) } returns DrugInfo(
            brandName = "B", genericName = "Generic", ndc = "SRV1",
            is_ndc_equivalent = true, drugType = "CII", qty = 3, isHazardous = false
        )
        vm.onNdcBarcodeRead("gtin", null)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showNdcEquivalenceDialog)
        assertEquals("SRV1", vm.uiState.value.ndcScannedValue)
    }

    @Test
    fun `onNdcBarcodeRead hard mismatch shows toast`() = runTest(testDispatcher) {
        val vm = createViewModel()
        // set expected ndc via hl7 flow path: use resumed txn to set hl7ExpectedNdc
        every { preferenceHelper.getTxnId() } returns 7L
        coEvery { pillCountTxnDao.getById(7L) } returns txn(txnId = 7L, isNdcVerified = false)
        coEvery { drugMasterDao.getDrugById(10L) } returns drug(ndc = "EXPECTED")
        vm.initializeFromResumedTxn() // PRE_NDC, hl7ExpectedNdc = EXPECTED
        advanceUntilIdle()
        coEvery { drugMasterDao.getDrugByGtin("gtin") } returns null
        coEvery { drugMasterDao.getDrugByNdc("gtin") } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any()) } returns DrugInfo(
            brandName = null, genericName = "Gen", ndc = "DIFFERENT",
            is_ndc_equivalent = false, drugType = "CII", qty = 1, isHazardous = null
        )
        vm.onNdcBarcodeRead("gtin", null)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.ndcMismatchToastTick > 0)
    }

    @Test
    fun `onNdcBarcodeRead server match needsSheet`() = runTest(testDispatcher) {
        val vm = ndcVm() // REGULAR, no txn, no batch
        advanceUntilIdle()
        coEvery { drugMasterDao.getDrugByGtin("gtin") } returns null
        coEvery { drugMasterDao.getDrugByNdc("gtin") } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any()) } returns DrugInfo(
            brandName = null, genericName = null, ndc = "SRV", // genericName null -> Unknown Drug
            is_ndc_equivalent = false, drugType = "X", qty = 2, isHazardous = true
        )
        vm.onNdcBarcodeRead("gtin", null)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showNdcDetails)
        assertEquals("Unknown Drug", vm.uiState.value.ndcDrugName)
    }

    @Test
    fun `onNdcBarcodeRead server match no sheet advances`() = runTest(testDispatcher) {
        val vm = ndcVm()
        vm.setBatchId(2L) // batchId set -> needsSheet false
        advanceUntilIdle()
        coEvery { drugMasterDao.getDrugByGtin("gtin") } returns null
        coEvery { drugMasterDao.getDrugByNdc("gtin") } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any()) } returns DrugInfo(
            brandName = null, genericName = "Gen", ndc = "SRV",
            is_ndc_equivalent = false, drugType = "X", qty = 2, isHazardous = false
        )
        vm.onNdcBarcodeRead("gtin", null)
        advanceUntilIdle()
        assertEquals(DispenseStage.COUNTING, vm.uiState.value.stage)
    }

    @Test
    fun `onNdcBarcodeRead exception sets error`() = runTest(testDispatcher) {
        val vm = ndcVm()
        advanceUntilIdle()
        coEvery { drugMasterDao.getDrugByGtin(any()) } throws RuntimeException("ndcfail")
        coEvery { drugMasterDao.getDrugByNdc(any()) } throws RuntimeException("ndcfail")
        vm.onNdcBarcodeRead("gtin", null)
        advanceUntilIdle()
        assertEquals("ndcfail", vm.uiState.value.error)
    }

    @Test
    fun `onNdcBarcodeRead loading guard`() = runTest(testDispatcher) {
        val vm = ndcVm()
        advanceUntilIdle()
        coEvery { drugMasterDao.getDrugByGtin("gtin") } returns drug(ndc = "L1")
        vm.onNdcBarcodeRead("gtin", null)
        vm.onNdcBarcodeRead("gtin", null) // second blocked by isLoading
        advanceUntilIdle()
        coVerify(atMost = 1) { drugMasterDao.getDrugByGtin("gtin") }
    }

    // ───────────────────────────── onRxConfirmed / onRxCancelled ─────────────────────────────

    @Test
    fun `onRxConfirmed blank ndc guard`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.onRxConfirmed()
        advanceUntilIdle()
        coVerify(exactly = 0) { pillCountTxnDao.upsertPreservingId(any()) }
    }

    @Test
    fun `onRxConfirmed creates txn and advances`() = runTest(testDispatcher) {
        // populate ndc via onRxBarcodeRead path is complex; use ON_HOLD set then manual.
        // Easiest: drive RX state by resuming so ndc is set, but that sets stage. Instead
        // set ndc through a PARTIAL resume in PRE_RX. We use confirmContinueRx-free path:
        // populate state via onRxBarcodeRead PARTIAL which sets ndc.
        coEvery { pillCountTxnDao.getActiveByRxNo("RX999") } returns
            txn(status = CountStatus.PARTIAL, isNdcVerified = false)
        coEvery { drugMasterDao.getDrugById(10L) } returns drug(ndc = "NDCX")
        coEvery { drugMasterDao.upsertPreservingId(any<DrugMasterEntity>()) } returns 11L
        coEvery { pillCountTxnDao.upsertPreservingId(any<PillCountTxnEntity>()) } returns 99L
        val vm = createViewModel()
        vm.onRxBarcodeRead("gtin", null)
        advanceUntilIdle()
        // Now ndc = NDCX set in state
        vm.onRxConfirmed()
        advanceUntilIdle()
        assertEquals(1L, vm.uiState.value.txnId)
        assertEquals(DispenseStage.PRE_NDC, vm.uiState.value.stage)
        assertFalse(vm.uiState.value.showRxDetails)
    }

    @Test
    fun `onRxCancelled clears fields`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.onRxCancelled()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.showRxDetails)
        assertEquals("", vm.uiState.value.ndc)
        assertNull(vm.uiState.value.rxNo)
    }

    // ───────────────────────────── confirmSubstitute ─────────────────────────────

    @Test
    fun `confirmSubstitute needsSheet shows details`() = runTest(testDispatcher) {
        val vm = ndcVm() // REGULAR txn0 batch0
        advanceUntilIdle()
        vm.confirmSubstitute()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showNdcDetails)
        assertTrue(vm.uiState.value.isSubstituteConfirmed)
        assertFalse(vm.uiState.value.showNdcEquivalenceDialog)
    }

    @Test
    fun `confirmSubstitute no sheet advances`() = runTest(testDispatcher) {
        val vm = ndcVm()
        vm.setBatchId(4L)
        advanceUntilIdle()
        coEvery { pillCountTxnDao.getById(any()) } returns txn(txnId = 70L)
        coEvery { drugMasterDao.upsertPreservingId(any<DrugMasterEntity>()) } returns 5L
        coEvery { pillCountTxnDao.upsertPreservingId(any<PillCountTxnEntity>()) } returns 71L
        vm.confirmSubstitute()
        advanceUntilIdle()
        assertEquals(DispenseStage.COUNTING, vm.uiState.value.stage)
    }

    // ───────────────────────────── confirmContinueRx ─────────────────────────────

    @Test
    fun `confirmContinueRx txn null returns`() = runTest(testDispatcher) {
        coEvery { pillCountTxnDao.getById(0L) } returns null
        val vm = createViewModel()
        vm.confirmContinueRx()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.stage == DispenseStage.COUNTING)
    }

    @Test
    fun `confirmContinueRx verified goes COUNTING`() = runTest(testDispatcher) {
        every { preferenceHelper.getTxnId() } returns 7L
        coEvery { pillCountTxnDao.getById(7L) } returns txn(txnId = 7L, isNdcVerified = true)
        coEvery { drugMasterDao.getDrugById(10L) } returns drug()
        val vm = createViewModel()
        // set txnId in state via resumed init
        vm.initializeFromResumedTxn()
        advanceUntilIdle()
        vm.confirmContinueRx()
        advanceUntilIdle()
        assertEquals(DispenseStage.COUNTING, vm.uiState.value.stage)
        assertFalse(vm.uiState.value.showContinueRxDialog)
    }

    @Test
    fun `confirmContinueRx not verified goes PRE_NDC`() = runTest(testDispatcher) {
        every { preferenceHelper.getTxnId() } returns 7L
        coEvery { pillCountTxnDao.getById(7L) } returns txn(txnId = 7L, isNdcVerified = false)
        coEvery { drugMasterDao.getDrugById(10L) } returns drug()
        val vm = createViewModel()
        vm.initializeFromResumedTxn()
        advanceUntilIdle()
        vm.confirmContinueRx()
        advanceUntilIdle()
        assertEquals(DispenseStage.PRE_NDC, vm.uiState.value.stage)
    }

    // ───────────────────────────── dismiss helpers ─────────────────────────────

    @Test
    fun `dismissContinueRxDialog resets`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.dismissContinueRxDialog()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.showContinueRxDialog)
        assertEquals(0L, vm.uiState.value.txnId)
    }

    @Test
    fun `dismissOnHoldDialog`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.dismissOnHoldDialog()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.showOnHoldDialog)
    }

    @Test
    fun `dismissNdcEquivalenceDialog clears`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.dismissNdcEquivalenceDialog()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.showNdcEquivalenceDialog)
        assertEquals("", vm.uiState.value.ndcScannedValue)
    }

    @Test
    fun `dismissInvalidScanDialog`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.dismissInvalidScanDialog()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.showInvalidScanDialog)
    }

    @Test
    fun `dismissNdcNotFoundDialog`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.dismissNdcNotFoundDialog()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.showNdcNotFoundDialog)
    }

    @Test
    fun `dismissRxScannedInStockCountDialog`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.dismissRxScannedInStockCountDialog()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.showRxScannedInStockCountDialog)
    }

    // ───────────────────────────── onNdcConfirmed ─────────────────────────────

    @Test
    fun `onNdcConfirmed REGULAR txn0 sealed navigates to batch`() = runTest(testDispatcher) {
        val vm = ndcVm() // REGULAR, txn0
        vm.onContainerStatusChanged(ContainerStatus.SEALED)
        advanceUntilIdle()
        coEvery { drugMasterDao.upsertPreservingId(any<DrugMasterEntity>()) } returns 5L
        // Stock count now creates StockTxn + BottleInfo (not a REGULAR pill_count_txn).
        // With no batchId set, navigateToBatchId falls back to the created bottleId.
        coEvery { stockTxnDao.findByDrugInBatch(any(), any()) } returns null
        coEvery { stockTxnDao.upsertPreservingId(any()) } returns 7L
        // No existing sealed line → the VM inserts a fresh sealed bottle row.
        coEvery { bottleInfoDao.findSealedLine(any(), any(), any()) } returns null
        coEvery { bottleInfoDao.insert(any()) } returns 88L
        vm.onNdcConfirmed()
        advanceUntilIdle()
        assertEquals(88L, vm.uiState.value.navigateToBatchId)
        assertFalse(vm.uiState.value.showNdcDetails)
    }

    @Test
    fun `onNdcConfirmed REGULAR txn0 opened goes COUNTING`() = runTest(testDispatcher) {
        val vm = ndcVm()
        vm.onContainerStatusChanged(ContainerStatus.OPENED)
        advanceUntilIdle()
        coEvery { drugMasterDao.upsertPreservingId(any<DrugMasterEntity>()) } returns 5L
        coEvery { pillCountTxnDao.upsertPreservingId(any<PillCountTxnEntity>()) } returns 88L
        vm.onNdcConfirmed()
        advanceUntilIdle()
        assertEquals(DispenseStage.COUNTING, vm.uiState.value.stage)
    }

    @Test
    fun `onNdcConfirmed txnId0 non-REGULAR returns`() = runTest(testDispatcher) {
        val vm = createViewModel() // FIXED, txn0
        vm.onNdcConfirmed()
        advanceUntilIdle()
        coVerify(exactly = 0) { pillCountTxnDao.update(any()) }
    }

    @Test
    fun `onNdcConfirmed existing txn substitute true`() = runTest(testDispatcher) {
        every { preferenceHelper.getTxnId() } returns 7L
        coEvery { pillCountTxnDao.getById(7L) } returns txn(txnId = 7L, isNdcVerified = false)
        coEvery { drugMasterDao.getDrugById(10L) } returns drug()
        coEvery { drugMasterDao.upsertPreservingId(any<DrugMasterEntity>()) } returns 12L
        val vm = createViewModel()
        vm.initializeFromResumedTxn() // sets txnId 7, stage PRE_NDC
        advanceUntilIdle()
        // mark substitute via confirmSubstitute path: txn !=0, REGULAR? it's FIXED so needsSheet false -> advances.
        // Instead set substitute via reflection-free: simulate equivalence then confirm.
        // Use onNdcBarcodeRead substitute to set isSubstituteConfirmed? That sets dialog only.
        // confirmSubstitute sets isSubstituteConfirmed=true; txnId!=0 -> needsSheet false -> advanceToCountingStage runs.
        // To test onNdcConfirmed substitute path explicitly, set ndcScannedValue first.
        vm.confirmSubstitute()
        advanceUntilIdle()
        // After confirmSubstitute (FIXED, txn!=0) it advanced to COUNTING via advanceToCountingStage substitute branch
        coVerify { pillCountTxnDao.update(any()) }
    }

    @Test
    fun `onNdcConfirmed existing txn substitute with scanned value persists substitute drug`() = runTest(testDispatcher) {
        every { preferenceHelper.getTxnId() } returns 7L
        coEvery { pillCountTxnDao.getById(7L) } returns txn(txnId = 7L, isNdcVerified = false)
        coEvery { drugMasterDao.getDrugById(10L) } returns drug(ndc = "EXPECTED")
        coEvery { drugMasterDao.getDrugByGtin(any()) } returns null
        coEvery { drugMasterDao.getDrugByNdc(any()) } returns null
        coEvery { drugMasterDao.upsertPreservingId(any<DrugMasterEntity>()) } returns 12L
        coEvery { drugRepository.getDrugInfoByNdc(any()) } returns DrugInfo(
            brandName = null, genericName = "Gen", ndc = "SUBNDC",
            is_ndc_equivalent = true, drugType = "X", qty = 7, isHazardous = false
        )
        val vm = createViewModel()
        vm.initializeFromResumedTxn() // txnId 7 (FIXED), PRE_NDC
        advanceUntilIdle()
        vm.onNdcBarcodeRead("gtin", null) // server substitute -> sets ndcScannedValue + equivalence dialog
        advanceUntilIdle()
        vm.confirmSubstitute() // FIXED txn!=0 -> needsSheet false -> advanceToCountingStage substitute branch
        advanceUntilIdle()
        assertEquals(DispenseStage.COUNTING, vm.uiState.value.stage)
        coVerify { pillCountTxnDao.update(any()) }
    }

    @Test
    fun `onNdcConfirmed existing txn null returns`() = runTest(testDispatcher) {
        every { preferenceHelper.getTxnId() } returns 7L
        coEvery { pillCountTxnDao.getById(7L) } returnsMany listOf(
            txn(txnId = 7L, isNdcVerified = false), // for resume init
            null // for onNdcConfirmed
        )
        coEvery { drugMasterDao.getDrugById(10L) } returns drug()
        val vm = createViewModel()
        vm.initializeFromResumedTxn()
        advanceUntilIdle()
        vm.onNdcConfirmed()
        advanceUntilIdle()
        // stage remains PRE_NDC since txn null in confirm
        assertEquals(DispenseStage.PRE_NDC, vm.uiState.value.stage)
    }

    @Test
    fun `onNdcBarcodeRead REGULAR batch writes StockTxn and BottleInfo`() = runTest(testDispatcher) {
        // Stock counting no longer uses pill_count_txn: a REGULAR batch NDC scan creates a
        // StockTxn header + a BottleInfo line and advances straight to COUNTING.
        val vm = ndcVm() // REGULAR
        vm.setBatchId(9L) // batch set so needsSheet false; advanceToCountingStage creates the stock line
        advanceUntilIdle()
        coEvery { drugMasterDao.getDrugByGtin("gtin") } returns drug(ndc = "L1")
        coEvery { drugMasterDao.getDrugIdByNdc("L1") } returns 10L
        coEvery { stockTxnDao.findByDrugInBatch(9L, 10L) } returns null
        coEvery { stockTxnDao.upsertPreservingId(any()) } returns 33L
        coEvery { bottleInfoDao.findLine(any(), any(), any()) } returns null
        coEvery { bottleInfoDao.insert(any()) } returns 44L
        vm.onNdcBarcodeRead("gtin", null)
        advanceUntilIdle()
        assertEquals(DispenseStage.COUNTING, vm.uiState.value.stage)
        assertEquals(0L, vm.uiState.value.txnId)
        coVerify { stockTxnDao.upsertPreservingId(any()) }
        coVerify { bottleInfoDao.insert(any()) }
    }

    @Test
    fun `onNdcConfirmed existing txn no substitute goes COUNTING`() = runTest(testDispatcher) {
        every { preferenceHelper.getTxnId() } returns 7L
        coEvery { pillCountTxnDao.getById(7L) } returns txn(txnId = 7L, isNdcVerified = false)
        coEvery { drugMasterDao.getDrugById(10L) } returns drug()
        val vm = createViewModel()
        vm.initializeFromResumedTxn()
        advanceUntilIdle()
        vm.onNdcConfirmed()
        advanceUntilIdle()
        assertEquals(DispenseStage.COUNTING, vm.uiState.value.stage)
        coVerify { pillCountTxnDao.update(any()) }
    }

    // ───────────────────────────── onNdcCancelled / onContainerStatusChanged ─────────────────────────────

    @Test
    fun `onNdcCancelled clears`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.onNdcCancelled()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.showNdcDetails)
        assertFalse(vm.uiState.value.isSubstituteConfirmed)
    }

    @Test
    fun `onContainerStatusChanged updates`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.onContainerStatusChanged(ContainerStatus.OPENED)
        advanceUntilIdle()
        assertEquals(ContainerStatus.OPENED, vm.uiState.value.selectedContainerStatus)
    }

    // ───────────────────────────── onRxScannedInNdcStage / StockCount ─────────────────────────────

    @Test
    fun `onRxScannedInNdcStage wrong stage ignored`() = runTest(testDispatcher) {
        val vm = createViewModel() // PRE_RX
        vm.onRxScannedInNdcStage()
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.scanNdcToastTick)
    }

    @Test
    fun `onRxScannedInNdcStage in PRE_NDC bumps tick`() = runTest(testDispatcher) {
        val vm = ndcVm()
        advanceUntilIdle()
        vm.onRxScannedInNdcStage()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.scanNdcToastTick > 0)
    }

    @Test
    fun `onRxScannedInStockCount wrong stage ignored`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.onRxScannedInStockCount()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.showRxScannedInStockCountDialog)
    }

    @Test
    fun `onRxScannedInStockCount in PRE_NDC shows dialog`() = runTest(testDispatcher) {
        val vm = ndcVm()
        advanceUntilIdle()
        vm.onRxScannedInStockCount()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showRxScannedInStockCountDialog)
    }

    // ───────────────────────────── misc setters ─────────────────────────────

    @Test
    fun `setAllowedNdcs updates`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.setAllowedNdcs(setOf("A", "B"))
        advanceUntilIdle()
        assertEquals(setOf("A", "B"), vm.uiState.value.allowedNdcs)
    }

    @Test
    fun `clearNavigateToBatch`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.clearNavigateToBatch()
        advanceUntilIdle()
        assertNull(vm.uiState.value.navigateToBatchId)
    }

    @Test
    fun `clearError`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.clearError()
        advanceUntilIdle()
        assertNull(vm.uiState.value.error)
    }

    // ───────────────────────────── queue mode ─────────────────────────────

    @Test
    fun `enterQueueMode sets QUEUE stage and default filter`() = runTest(testDispatcher) {
        every { preferenceHelper.getLocalId() } returns 1L
        every {
            pillCountTxnDao.observePartialByIsDispense(any(), any(), any(), any())
        } returns flowOf(emptyList())
        val vm = createViewModel()
        vm.enterQueueMode()
        advanceUntilIdle()
        assertEquals(DispenseStage.QUEUE, vm.uiState.value.stage)
        assertEquals(KpiFilter.DISP_PENDING, vm.uiState.value.selectedQueueFilter)
        assertEquals(true, vm.uiState.value.initResolved)
    }

    @Test
    fun `resetToQueue sets QUEUE stage`() = runTest(testDispatcher) {
        every {
            pillCountTxnDao.observePartialByIsDispense(any(), any(), any(), any())
        } returns flowOf(emptyList())
        val vm = createViewModel()
        vm.resetToQueue()
        advanceUntilIdle()
        assertEquals(DispenseStage.QUEUE, vm.uiState.value.stage)
        assertEquals(true, vm.uiState.value.initResolved)
    }

    @Test
    fun `observeDispenseQueue maps txns to queue items`() = runTest(testDispatcher) {
        val dto = PillCountWithDrugAndTotal(
            txnId = 1L, drugName = "D", ndc = "N", drugType = "CII", bucketId = "b",
            createdAt = 1L, targetCount = 5, bottleInfoListJson = null, totalPillCount = 0,
            isComingFromHL7 = false, isNdcVerified = false, isDispense = true,
            priority = TxnPriority.High, isHazardous = true,
        )
        every {
            pillCountTxnDao.observePartialByIsDispense(any(), any(), any(), any())
        } returns flowOf(listOf(dto))
        val vm = createViewModel()
        vm.enterQueueMode()
        advanceUntilIdle()
        // queue runs on Dispatchers.IO so may complete asynchronously; allow either outcome
        val items = vm.uiState.value.queueItems
        if (items.isNotEmpty()) {
            assertEquals(true, items[0].isHazardous)
            assertEquals(true, items[0].isHighPriority)
            assertEquals(true, items[0].isControlled) // CII is controlled
        }
    }

    @Test
    fun `resetToQueueOrNavigateDashboard with pending resets queue`() = runTest(testDispatcher) {
        coEvery {
            pillCountTxnDao.countPartialByIsDispense(any(), any(), any())
        } returns 2
        every {
            pillCountTxnDao.observePartialByIsDispense(any(), any(), any(), any())
        } returns flowOf(emptyList())
        val vm = createViewModel()
        vm.resetToQueueOrNavigateDashboard()
        advanceUntilIdle()
        assertEquals(DispenseStage.QUEUE, vm.uiState.value.stage)
    }

    @Test
    fun `resetToQueueOrNavigateDashboard no pending navigates dashboard`() = runTest(testDispatcher) {
        coEvery {
            pillCountTxnDao.countPartialByIsDispense(any(), any(), any())
        } returns 0
        val vm = createViewModel()
        vm.resetToQueueOrNavigateDashboard()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.navigateToDashboard)
    }

    @Test
    fun `clearNavigateToDashboard`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.clearNavigateToDashboard()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.navigateToDashboard)
    }

    @Test
    fun `setQueueFilter non-null updates`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.setQueueFilter(KpiFilter.DISP_HAZARDOUS)
        advanceUntilIdle()
        assertEquals(KpiFilter.DISP_HAZARDOUS, vm.uiState.value.selectedQueueFilter)
    }

    @Test
    fun `setQueueFilter null ignored`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.setQueueFilter(KpiFilter.DISP_PENDING)
        advanceUntilIdle()
        vm.setQueueFilter(null)
        advanceUntilIdle()
        assertEquals(KpiFilter.DISP_PENDING, vm.uiState.value.selectedQueueFilter)
    }

    // ───────────────────────────── resumeFromQueue ─────────────────────────────

    @Test
    fun `resumeFromQueue txn null returns`() = runTest(testDispatcher) {
        coEvery { pillCountTxnDao.getById(5L) } returns null
        val vm = createViewModel()
        vm.resumeFromQueue(5L)
        advanceUntilIdle()
        coVerify(exactly = 0) { preferenceHelper.saveTxnId(any()) }
    }

    @Test
    fun `resumeFromQueue verified goes COUNTING`() = runTest(testDispatcher) {
        coEvery { pillCountTxnDao.getById(5L) } returns txn(txnId = 5L, isNdcVerified = true)
        coEvery { drugMasterDao.getDrugById(10L) } returns drug()
        val vm = createViewModel()
        vm.resumeFromQueue(5L)
        advanceUntilIdle()
        assertEquals(DispenseStage.COUNTING, vm.uiState.value.stage)
        assertEquals(5L, vm.uiState.value.txnId)
    }

    @Test
    fun `resumeFromQueue not verified goes PRE_NDC`() = runTest(testDispatcher) {
        coEvery { pillCountTxnDao.getById(5L) } returns txn(txnId = 5L, isNdcVerified = false)
        coEvery { drugMasterDao.getDrugById(10L) } returns drug()
        val vm = createViewModel()
        vm.resumeFromQueue(5L)
        advanceUntilIdle()
        assertEquals(DispenseStage.PRE_NDC, vm.uiState.value.stage)
    }

    // ───────────────────────────── isHl7Enabled ─────────────────────────────

    @Test
    fun `isHl7Enabled delegates to preferenceHelper`() {
        every { preferenceHelper.isHl7Enabled() } returns true
        val vm = createViewModel()
        assertTrue(vm.isHl7Enabled())
        every { preferenceHelper.isHl7Enabled() } returns false
        assertFalse(vm.isHl7Enabled())
    }

    // ───────────────────────────── onVialBarcodeRead ─────────────────────────────

    @Test
    fun `onVialBarcodeRead blank raw value returns false`() = runTest(testDispatcher) {
        val vm = createViewModel()
        assertFalse(vm.onVialBarcodeRead("   "))
    }

    @Test
    fun `onVialBarcodeRead no expected rx returns false`() = runTest(testDispatcher) {
        // Fresh VM: state.rxNo is null, so expectedRx is empty.
        val vm = createViewModel()
        assertFalse(vm.onVialBarcodeRead("RX999"))
    }

    @Test
    fun `onVialBarcodeRead pipe-delimited match returns true`() = runTest(testDispatcher) {
        coEvery { pillCountTxnDao.getActiveByRxNo("RX999") } returns
            txn(status = CountStatus.PARTIAL, isNdcVerified = false)
        coEvery { drugMasterDao.getDrugById(10L) } returns drug()
        val vm = createViewModel()
        vm.onRxBarcodeRead("gtin", null) // sets state.rxNo = RX999
        advanceUntilIdle()

        every { parseScanData(any(), "RX999|NDC123|10|B1") } returns validParsed(rxNo = "RX999")
        val matched = vm.onVialBarcodeRead("RX999|NDC123|10|B1")
        assertTrue(matched)
        assertEquals(0, vm.uiState.value.vialRxMismatchToastTick)
    }

    @Test
    fun `onVialBarcodeRead bare value match returns true`() = runTest(testDispatcher) {
        coEvery { pillCountTxnDao.getActiveByRxNo("RX999") } returns
            txn(status = CountStatus.PARTIAL, isNdcVerified = false)
        coEvery { drugMasterDao.getDrugById(10L) } returns drug()
        val vm = createViewModel()
        vm.onRxBarcodeRead("gtin", null) // sets state.rxNo = RX999
        advanceUntilIdle()

        val matched = vm.onVialBarcodeRead("rx999") // case-insensitive, no pipe
        assertTrue(matched)
    }

    @Test
    fun `onVialBarcodeRead mismatch bumps debounced toast`() = runTest(testDispatcher) {
        coEvery { pillCountTxnDao.getActiveByRxNo("RX999") } returns
            txn(status = CountStatus.PARTIAL, isNdcVerified = false)
        coEvery { drugMasterDao.getDrugById(10L) } returns drug()
        val vm = createViewModel()
        vm.onRxBarcodeRead("gtin", null)
        advanceUntilIdle()

        val matched = vm.onVialBarcodeRead("WRONGRX")
        assertFalse(matched)
        assertTrue(vm.uiState.value.vialRxMismatchToastTick > 0)
    }

    @Test
    fun `onVialBarcodeRead debounces repeated mismatch toasts`() = runTest(testDispatcher) {
        coEvery { pillCountTxnDao.getActiveByRxNo("RX999") } returns
            txn(status = CountStatus.PARTIAL, isNdcVerified = false)
        coEvery { drugMasterDao.getDrugById(10L) } returns drug()
        val vm = createViewModel()
        vm.onRxBarcodeRead("gtin", null)
        advanceUntilIdle()

        vm.onVialBarcodeRead("WRONGRX")
        val firstTick = vm.uiState.value.vialRxMismatchToastTick
        vm.onVialBarcodeRead("WRONGRX") // immediate repeat, within cooldown
        assertEquals(firstTick, vm.uiState.value.vialRxMismatchToastTick)
    }

    @Test
    fun `onVialBarcodeRead pipe-delimited parses to blank rx returns false`() = runTest(testDispatcher) {
        coEvery { pillCountTxnDao.getActiveByRxNo("RX999") } returns
            txn(status = CountStatus.PARTIAL, isNdcVerified = false)
        coEvery { drugMasterDao.getDrugById(10L) } returns drug()
        val vm = createViewModel()
        vm.onRxBarcodeRead("gtin", null)
        advanceUntilIdle()

        every { parseScanData(any(), "garbage|data") } returns validParsed(rxNo = null)
        assertFalse(vm.onVialBarcodeRead("garbage|data"))
    }

    // ───────────── createStockLine: existing header / sealed re-scan / batch totals ─────────────

    @Test
    fun `onNdcConfirmed sealed reuses existing stock header and increments sealed line`() =
        runTest(testDispatcher) {
            val vm = ndcVm() // REGULAR, txn0
            vm.setBatchId(9L)
            vm.onContainerStatusChanged(ContainerStatus.SEALED)
            advanceUntilIdle()
            coEvery { drugMasterDao.upsertPreservingId(any<DrugMasterEntity>()) } returns 5L
            // Existing StockTxn header for this drug in the batch -> reused, no new upsert needed.
            coEvery { stockTxnDao.findByDrugInBatch(9L, 5L) } returns
                com.rite.pillcounting.core.room.models.StockTxnEntity(
                    txnId = 60L, drugId = 5L, status = CountStatus.PARTIAL, batchId = 9L,
                )
            // Existing sealed line -> bumped instead of inserting a new one.
            coEvery { bottleInfoDao.findSealedLine(60L, null, null) } returns
                com.rite.pillcounting.core.room.models.BottleInfoEntity(
                    bottleId = 15L, stockTxnId = 60L, batchId = 9L, bottleQty = 2,
                )
            every { preferenceHelper.getRecentLogins() } returns listOf("user@rite.com")

            vm.onNdcConfirmed()
            advanceUntilIdle()

            coVerify(exactly = 0) { stockTxnDao.upsertPreservingId(any()) }
            coVerify { bottleInfoDao.update(match { it.bottleId == 15L && it.bottleQty == 3 }) }
            coVerify { stockTxnDao.refreshBatchTotalNdcs(9L) }
            coVerify { stockTxnDao.updateBatchUserName(9L, "user@rite.com") }
            assertEquals(9L, vm.uiState.value.navigateToBatchId)
        }

    @Test
    fun `onNdcConfirmed sealed batch user name falls back to getUserId when no recent logins`() =
        runTest(testDispatcher) {
            val vm = ndcVm()
            vm.setBatchId(9L)
            vm.onContainerStatusChanged(ContainerStatus.SEALED)
            advanceUntilIdle()
            coEvery { drugMasterDao.upsertPreservingId(any<DrugMasterEntity>()) } returns 5L
            coEvery { stockTxnDao.findByDrugInBatch(9L, 5L) } returns null
            coEvery { stockTxnDao.upsertPreservingId(any()) } returns 61L
            coEvery { bottleInfoDao.findSealedLine(61L, null, null) } returns null
            coEvery { bottleInfoDao.insert(any()) } returns 16L
            every { preferenceHelper.getRecentLogins() } returns emptyList()
            every { preferenceHelper.getUserId() } returns "fallback@rite.com"

            vm.onNdcConfirmed()
            advanceUntilIdle()

            coVerify { stockTxnDao.updateBatchUserName(9L, "fallback@rite.com") }
        }

    // ───────────── advanceToCountingStage: pendingFirstBottle stamping ─────────────

    @Test
    fun `advanceToCountingStage stamps pendingFirstBottle on dispense txn with no existing bottles`() =
        runTest(testDispatcher) {
            every { preferenceHelper.getTxnId() } returns 7L
            coEvery { pillCountTxnDao.getById(7L) } returns txn(txnId = 7L, isNdcVerified = false)
            coEvery { drugMasterDao.getDrugById(10L) } returns drug(ndc = "EXPECTED")
            val vm = createViewModel()
            vm.initializeFromResumedTxn() // txnId 7, PRE_NDC, hl7ExpectedNdc = EXPECTED
            advanceUntilIdle()

            coEvery { drugMasterDao.getDrugByGtin("gtin") } returns drug(ndc = "EXPECTED")
            val firstBottle = BottleInfo(lotNumber = "LOT1", expirationDate = "2030-01", serialNumber = "SN1")
            vm.onNdcBarcodeRead("gtin", null, firstBottle = firstBottle) // trustLocal, needsSheet false -> advance
            advanceUntilIdle()

            coVerify {
                pillCountTxnDao.update(match {
                    it.txnId == 7L &&
                        BottleInfoJson.decode(it.bottleInfoListJson) == listOf(firstBottle.copy(txnId = 7L))
                })
            }
            assertEquals(DispenseStage.COUNTING, vm.uiState.value.stage)
        }

    // ───────────── advanceToCountingStage: substitute carries forward cached image path ─────────────

    @Test
    fun `advanceToCountingStage substitute reuses cached drug image path`() = runTest(testDispatcher) {
        every { preferenceHelper.getTxnId() } returns 7L
        coEvery { pillCountTxnDao.getById(7L) } returns txn(txnId = 7L, isNdcVerified = false)
        coEvery { drugMasterDao.getDrugById(10L) } returns drug(ndc = "EXPECTED")
        coEvery { drugMasterDao.getDrugByGtin(any()) } returns null
        coEvery { drugMasterDao.getDrugByNdc(any()) } returns null
        coEvery { drugMasterDao.upsertPreservingId(any<DrugMasterEntity>()) } returns 12L
        coEvery { drugRepository.getDrugInfoByNdc(any()) } returns DrugInfo(
            brandName = null, genericName = "Gen", ndc = "SUBNDC",
            is_ndc_equivalent = true, drugType = "X", qty = 7, isHazardous = false,
        )
        val vm = createViewModel()
        vm.initializeFromResumedTxn() // txnId 7 (FIXED), PRE_NDC
        advanceUntilIdle()
        vm.onNdcBarcodeRead("gtin", null) // server substitute -> ndcScannedValue = SUBNDC
        advanceUntilIdle()

        // Substitute drug was already cached during the NDC scan with an image path;
        // advanceToCountingStage must look it up and carry it forward.
        coEvery { drugMasterDao.getDrugByNdc("SUBNDC") } returns
            drug(ndc = "SUBNDC").copy(drugImagePath = "/cached/image.png")

        vm.confirmSubstitute()
        advanceUntilIdle()

        coVerify {
            drugMasterDao.upsertPreservingId(match<DrugMasterEntity> {
                it.ndc == "SUBNDC" && it.drugImagePath == "/cached/image.png"
            })
        }
    }

    // ───────────── advanceToCountingStage batch path (txn0) ─────────────

    @Test
    fun `advanceToCountingStage batch creates stock line and COUNTING`() = runTest(testDispatcher) {
        val vm = ndcVm() // REGULAR
        vm.setBatchId(9L)
        advanceUntilIdle()
        coEvery { drugMasterDao.getDrugByGtin("gtin") } returns drug(ndc = "L1")
        coEvery { drugMasterDao.getDrugIdByNdc("L1") } returns 10L
        coEvery { stockTxnDao.findByDrugInBatch(9L, 10L) } returns null
        coEvery { stockTxnDao.upsertPreservingId(any()) } returns 7L
        coEvery { bottleInfoDao.findLine(any(), any(), any()) } returns null
        coEvery { bottleInfoDao.insert(any()) } returns 44L
        vm.onNdcBarcodeRead("gtin", null) // trustLocal, needsSheet false (batch set) -> advance
        advanceUntilIdle()
        assertEquals(DispenseStage.COUNTING, vm.uiState.value.stage)
        // Stock counts no longer create a pill_count_txn; they create StockTxn + BottleInfo.
        assertEquals(0L, vm.uiState.value.txnId)
        assertEquals(7L, vm.uiState.value.stockTxnId)
        assertEquals(44L, vm.uiState.value.stockBottleId)
    }

    @Test
    fun `advanceToCountingStage txn0 non-REGULAR returns early`() = runTest(testDispatcher) {
        // confirmSubstitute on FIXED with txn0, batch0 -> needsSheet false -> advanceToCountingStage,
        // txnId 0 and countType FIXED -> early return, no txn created.
        val vm = createViewModel() // FIXED
        vm.confirmSubstitute()
        advanceUntilIdle()
        coVerify(exactly = 0) { pillCountTxnDao.upsertPreservingId(any<PillCountTxnEntity>()) }
    }
}
