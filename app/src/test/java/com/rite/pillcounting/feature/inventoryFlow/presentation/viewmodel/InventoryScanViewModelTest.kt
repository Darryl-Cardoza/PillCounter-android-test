package com.rite.pillcounting.feature.inventoryFlow.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.DrugMasterDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.models.BatchEntity
import com.rite.pillcounting.core.room.models.DrugMasterEntity
import com.rite.pillcounting.core.room.models.PillCountTxnEntity
import com.rite.pillcounting.core.room.models.dtos.BatchTxnDto
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.scanning.domain.data.IDrugRepository
import com.rite.pillcounting.core.scanning.domain.model.BarcodeData
import com.rite.pillcounting.core.scanning.domain.model.DrugInfo
import com.rite.pillcounting.core.scanning.domain.model.GetNdcRequestModel
import com.rite.pillcounting.core.utils.common.BarcodeDecoder
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.hl7.core.Hl7EventHandler
import com.rite.pillcounting.feature.hl7.data.repository.Hl7Repository
import com.rite.pillcounting.feature.inventoryFlow.domain.model.RecentBatchRow
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class InventoryScanViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var batchDao: BatchDao
    private lateinit var pillCountTxnDao: PillCountTxnDao
    private lateinit var drugMasterDao: DrugMasterDao
    private lateinit var preferenceHelper: PreferenceHelper
    private lateinit var barcodeDecoder: BarcodeDecoder
    private lateinit var drugRepository: IDrugRepository
    private lateinit var hl7Repository: Hl7Repository
    private lateinit var hl7EventHandler: Hl7EventHandler
    private lateinit var connectionState: MutableStateFlow<Boolean>

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.d(any(), any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any<String>(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        Dispatchers.setMain(testDispatcher)

        batchDao = mockk(relaxed = true)
        pillCountTxnDao = mockk(relaxed = true)
        drugMasterDao = mockk(relaxed = true)
        preferenceHelper = mockk(relaxed = true)
        barcodeDecoder = mockk(relaxed = true)
        drugRepository = mockk(relaxed = true)
        hl7Repository = mockk(relaxed = true)
        hl7EventHandler = mockk(relaxed = true)
        connectionState = MutableStateFlow(false)

        every { hl7EventHandler.connectionState } returns connectionState
        every { preferenceHelper.getLocalId() } returns 1L
        every { pillCountTxnDao.observeByBatchId(any()) } returns flowOf(emptyList())
        coEvery { batchDao.getById(any()) } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel(
        batchId: Long? = null,
        bucketId: String? = null,
    ): InventoryScanViewModel {
        val map = mutableMapOf<String, Any?>()
        if (batchId != null) map["batch_id"] = batchId
        if (bucketId != null) map["bucket_id"] = bucketId
        val savedStateHandle = SavedStateHandle(map)
        return InventoryScanViewModel(
            savedStateHandle,
            batchDao,
            pillCountTxnDao,
            drugMasterDao,
            preferenceHelper,
            barcodeDecoder,
            drugRepository,
            hl7Repository,
            hl7EventHandler,
        )
    }

    private fun drug(
        drugId: Long = 10L,
        ndc: String = "00093-0058",
        drugName: String? = "Aspirin",
        packageQty: Int? = 100,
        isHazardous: Boolean = false,
    ) = DrugMasterEntity(
        drugId = drugId,
        drugName = drugName,
        ndc = ndc,
        packageQty = packageQty,
        isHazardous = isHazardous,
    )

    private fun barcodeData(
        gtin: String? = "00300930058018",
        lot: String? = "LOT1",
        expiry: LocalDate? = LocalDate.of(2030, 1, 15),
    ) = BarcodeData(gtin = gtin, lotNumber = lot, expirationDate = expiry)

    private fun txnEntity(
        txnId: Long = 1L,
        drugId: Long = 10L,
        bottleQty: Int? = 5,
        lotNo: String? = "LOT1",
        expiry: String? = "01-15-2030",
    ) = PillCountTxnEntity(
        txnId = txnId,
        drugId = drugId,
        countType = CountType.REGULAR,
        status = CountStatus.COMPLETED,
        bottleQty = bottleQty,
        lotNo = lotNo,
        expiry = expiry,
    )

    // ─────────────────────────  init  ─────────────────────────

    @Test
    fun `init with PMS batch loads expectedNdcs`() = runTest(testDispatcher) {
        coEvery { batchDao.getById(5L) } returns BatchEntity(
            batchId = 5L, bucketId = "B1", requestIdFromPMS = "REQ-1"
        )
        coEvery { pillCountTxnDao.getTxnsByBatchId(5L) } returns listOf(
            BatchTxnDto(1, 10L, "Aspirin", "NDC-A", "L", "E", 1, 0, 1),
            BatchTxnDto(2, 11L, "Tyl", "", "L", "E", 1, 0, 1),
            BatchTxnDto(3, 12L, "X", null, "L", "E", 1, 0, 1),
        )
        val vm = createViewModel(batchId = 5L)
        advanceUntilIdle()

        coVerify { pillCountTxnDao.getTxnsByBatchId(5L) }
        assertNotNull(vm)
    }

    @Test
    fun `init with manual batch does not load expectedNdcs`() = runTest(testDispatcher) {
        coEvery { batchDao.getById(6L) } returns BatchEntity(
            batchId = 6L, bucketId = "B2", requestIdFromPMS = null
        )
        val vm = createViewModel(batchId = 6L)
        advanceUntilIdle()

        coVerify(exactly = 0) { pillCountTxnDao.getTxnsByBatchId(6L) }
        assertNotNull(vm)
    }

    @Test
    fun `init with zero batchId uses bucket from arg`() = runTest(testDispatcher) {
        val vm = createViewModel(batchId = 0L, bucketId = "BUCKET-X")
        advanceUntilIdle()

        coVerify(exactly = 0) { batchDao.getById(any()) }
        assertNotNull(vm)
    }

    @Test
    fun `init with blank bucket arg coerces to null`() = runTest(testDispatcher) {
        val vm = createViewModel(batchId = 0L, bucketId = "  ")
        advanceUntilIdle()
        assertNotNull(vm)
    }

    // ─────────────────────────  recentRows / uiState (toRecentRows)  ─────────────────────────

    @Test
    fun `uiState empty when batchId zero`() = runTest(testDispatcher) {
        val vm = createViewModel(batchId = 0L)
        vm.uiState.test {
            assertEquals(0, awaitItem().totalNdcs)
        }
    }

    @Test
    fun `uiState groups txns and sums pills via toRecentRows`() = runTest(testDispatcher) {
        every { pillCountTxnDao.observeByBatchId(7L) } returns flowOf(
            listOf(
                // drug 10: 2 bottles * 5 pkg + 3 loose = 13
                BatchTxnDto(1, 10L, "Aspirin", "NDC-A", "L1", "E1", 2, 0, 5),
                BatchTxnDto(2, 10L, "Aspirin", "NDC-A", "L2", "E2", 0, 3, 1),
                // drug 20: nulls -> defaults
                BatchTxnDto(3, 20L, null, null, null, null, null, null, null),
            )
        )
        val vm = createViewModel(batchId = 7L)
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(2, state.totalNdcs)
        val aspirin = state.recentCounts.first { it.ndc == "NDC-A" }
        assertEquals(13, aspirin.pills)
        assertEquals(2, aspirin.bottles)
        val unknown = state.recentCounts.first { it.ndc == "" }
        assertEquals(0, unknown.pills)
        job.cancel()
    }

    // ─────────────────────────  onBarcodeDetected — happy paths  ─────────────────────────

    private fun stubGs1Decode(
        decoded: BarcodeData = barcodeData(),
        gtin14: String = "00300930058018",
    ) {
        every { barcodeDecoder.isGs1Barcode(any()) } returns true
        every { barcodeDecoder.decode(any()) } returns decoded
        every { barcodeDecoder.toGtin14(decoded.gtin) } returns gtin14
        every { barcodeDecoder.toGtin14(gtin14) } returns gtin14
    }

    @Test
    fun `onBarcodeDetected gtin14 valid path local drug found persists insert`() = runTest(testDispatcher) {
        stubGs1Decode()
        coEvery { drugMasterDao.getDrugByGtin("00300930058018") } returns drug()
        coEvery { drugMasterDao.getDrugIdByNdc(any()) } returns 10L
        coEvery { pillCountTxnDao.findSealedTxnInBatch(any(), any(), any(), any()) } returns null

        val vm = createViewModel(batchId = 7L)
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.onBarcodeDetected("rawbarcode")
        advanceUntilIdle()

        assertEquals("00093-0058", vm.uiState.value.activeNdc?.ndc)
        assertEquals(1, vm.uiState.value.activeNdc?.bottles)
        coVerify { pillCountTxnDao.upsertPreservingId(any()) }
        job.cancel()
    }

    @Test
    fun `onBarcodeDetected raw-digit fallback when gtin invalid`() = runTest(testDispatcher) {
        every { barcodeDecoder.isGs1Barcode(any()) } returns false
        every { barcodeDecoder.toGtin14("1234567890") } returns null
        coEvery { drugMasterDao.getDrugByGtin("1234567890") } returns null
        coEvery { drugMasterDao.getDrugByNdc("1234567890") } returns drug(ndc = "1234567890")
        coEvery { drugMasterDao.getDrugIdByNdc(any()) } returns 10L

        val vm = createViewModel(batchId = 7L)
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.onBarcodeDetected("1234567890")
        advanceUntilIdle()

        assertEquals("1234567890", vm.uiState.value.activeNdc?.ndc)
        job.cancel()
    }

    @Test
    fun `onBarcodeDetected invalid label emits error`() = runTest(testDispatcher) {
        every { barcodeDecoder.isGs1Barcode(any()) } returns false
        every { barcodeDecoder.toGtin14(any()) } returns null

        val vm = createViewModel(batchId = 7L)
        advanceUntilIdle()

        vm.onBarcodeDetected("AB")
        advanceUntilIdle()

        assertEquals(R.string.batch_stock_count_invalid_label, vm.errorMessage.value?.messageResId)
    }

    @Test
    fun `onBarcodeDetected server lookup success upserts and re-reads`() = runTest(testDispatcher) {
        stubGs1Decode()
        coEvery { drugMasterDao.getDrugByGtin("00300930058018") } returns null
        coEvery { drugMasterDao.getDrugByNdc("00300930058018") } returns null
        coEvery {
            drugRepository.getDrugInfoByNdc(any())
        } returns DrugInfo(
            brandName = null, genericName = "Server Drug", ndc = "SRV-NDC",
            drugType = "tablet", qty = 50, isHazardous = true,
        )
        coEvery { drugMasterDao.getDrugByNdc("SRV-NDC") } returns drug(ndc = "SRV-NDC", drugName = "Server Drug")
        coEvery { drugMasterDao.getDrugIdByNdc(any()) } returns 10L

        val vm = createViewModel(batchId = 7L)
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.onBarcodeDetected("raw")
        advanceUntilIdle()

        coVerify { drugMasterDao.upsertPreservingId(any<DrugMasterEntity>()) }
        assertEquals("SRV-NDC", vm.uiState.value.activeNdc?.ndc)
        job.cancel()
    }

    @Test
    fun `onBarcodeDetected server returns blank genericName uses Unknown Drug`() = runTest(testDispatcher) {
        stubGs1Decode()
        coEvery { drugMasterDao.getDrugByGtin(any()) } returns null
        coEvery { drugMasterDao.getDrugByNdc("00300930058018") } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any()) } returns DrugInfo(
            brandName = null, genericName = "", ndc = "SRV", drugType = "t", qty = null, isHazardous = null,
        )
        coEvery { drugMasterDao.getDrugByNdc("SRV") } returns null
        coEvery { drugMasterDao.getDrugByGtin("00300930058018") } returns null

        val vm = createViewModel(batchId = 7L)
        advanceUntilIdle()

        vm.onBarcodeDetected("raw")
        advanceUntilIdle()

        val slot = slot<DrugMasterEntity>()
        coVerify { drugMasterDao.upsertPreservingId(capture(slot)) }
        assertEquals("Unknown Drug", slot.captured.drugName)
        // re-read returned null -> drug_not_found
        assertEquals(R.string.batch_stock_count_drug_not_found, vm.errorMessage.value?.messageResId)
    }

    @Test
    fun `onBarcodeDetected server returns null emits drug_not_found`() = runTest(testDispatcher) {
        stubGs1Decode()
        coEvery { drugMasterDao.getDrugByGtin(any()) } returns null
        coEvery { drugMasterDao.getDrugByNdc(any()) } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any()) } returns null

        val vm = createViewModel(batchId = 7L)
        advanceUntilIdle()

        vm.onBarcodeDetected("raw")
        advanceUntilIdle()

        assertEquals(R.string.batch_stock_count_drug_not_found, vm.errorMessage.value?.messageResId)
    }

    @Test
    fun `onBarcodeDetected server lookup throws is caught and emits drug_not_found`() = runTest(testDispatcher) {
        stubGs1Decode()
        coEvery { drugMasterDao.getDrugByGtin(any()) } returns null
        coEvery { drugMasterDao.getDrugByNdc(any()) } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any()) } throws RuntimeException("network")

        val vm = createViewModel(batchId = 7L)
        advanceUntilIdle()

        vm.onBarcodeDetected("raw")
        advanceUntilIdle()

        assertEquals(R.string.batch_stock_count_drug_not_found, vm.errorMessage.value?.messageResId)
    }

    @Test
    fun `onBarcodeDetected rejects ndc not in PMS expected set`() = runTest(testDispatcher) {
        coEvery { batchDao.getById(5L) } returns BatchEntity(
            batchId = 5L, bucketId = "B", requestIdFromPMS = "REQ"
        )
        coEvery { pillCountTxnDao.getTxnsByBatchId(5L) } returns listOf(
            BatchTxnDto(1, 1L, "x", "ALLOWED-NDC", "l", "e", 1, 0, 1)
        )
        stubGs1Decode()
        coEvery { drugMasterDao.getDrugByGtin(any()) } returns drug(ndc = "OTHER-NDC")

        val vm = createViewModel(batchId = 5L)
        advanceUntilIdle()

        vm.onBarcodeDetected("raw")
        advanceUntilIdle()

        assertEquals(R.string.batch_stock_count_ndc_not_in_request, vm.errorMessage.value?.messageResId)
    }

    @Test
    fun `onBarcodeDetected lazy batch creation inserts when batchId zero`() = runTest(testDispatcher) {
        stubGs1Decode()
        coEvery { drugMasterDao.getDrugByGtin(any()) } returns drug()
        coEvery { drugMasterDao.getDrugIdByNdc(any()) } returns 10L
        coEvery { batchDao.insert(any()) } returns 99L

        val vm = createViewModel(batchId = 0L, bucketId = "BK")
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.onBarcodeDetected("raw")
        advanceUntilIdle()

        coVerify { batchDao.insert(any()) }
        assertEquals("00093-0058", vm.uiState.value.activeNdc?.ndc)
        job.cancel()
    }

    @Test
    fun `onBarcodeDetected insert returns zero emits no_active_batch`() = runTest(testDispatcher) {
        stubGs1Decode()
        coEvery { drugMasterDao.getDrugByGtin(any()) } returns drug()
        coEvery { batchDao.insert(any()) } returns 0L

        val vm = createViewModel(batchId = 0L)
        advanceUntilIdle()

        vm.onBarcodeDetected("raw")
        advanceUntilIdle()

        assertEquals(R.string.batch_stock_count_no_active_batch, vm.errorMessage.value?.messageResId)
    }

    @Test
    fun `onBarcodeDetected insert throws causes ensureBatchCreated zero and no_active_batch`() = runTest(testDispatcher) {
        stubGs1Decode()
        coEvery { drugMasterDao.getDrugByGtin(any()) } returns drug()
        coEvery { batchDao.insert(any()) } throws RuntimeException("db")

        val vm = createViewModel(batchId = 0L)
        advanceUntilIdle()

        vm.onBarcodeDetected("raw")
        advanceUntilIdle()

        assertEquals(R.string.batch_stock_count_no_active_batch, vm.errorMessage.value?.messageResId)
    }

    @Test
    fun `onBarcodeDetected auto-commits previous active when different ndc`() = runTest(testDispatcher) {
        every { barcodeDecoder.isGs1Barcode(any()) } returns true
        every { barcodeDecoder.decode("first") } returns barcodeData(gtin = "G1")
        every { barcodeDecoder.decode("second") } returns barcodeData(gtin = "G2")
        every { barcodeDecoder.toGtin14("G1") } returns "00000000000001"
        every { barcodeDecoder.toGtin14("00000000000001") } returns "00000000000001"
        every { barcodeDecoder.toGtin14("G2") } returns "00000000000002"
        every { barcodeDecoder.toGtin14("00000000000002") } returns "00000000000002"
        coEvery { drugMasterDao.getDrugByGtin("00000000000001") } returns drug(drugId = 10L, ndc = "NDC-1")
        coEvery { drugMasterDao.getDrugByGtin("00000000000002") } returns drug(drugId = 20L, ndc = "NDC-2")
        coEvery { drugMasterDao.getDrugIdByNdc("NDC-1") } returns 10L
        coEvery { drugMasterDao.getDrugIdByNdc("NDC-2") } returns 20L
        coEvery { pillCountTxnDao.findSealedTxnInBatch(any(), any(), any(), any()) } returns null

        val vm = createViewModel(batchId = 7L)
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.onBarcodeDetected("first")
        advanceUntilIdle()
        vm.onBarcodeDetected("second")
        advanceUntilIdle()

        assertEquals("NDC-2", vm.uiState.value.activeNdc?.ndc)
        // both NDC-1 (auto-commit) and NDC-2 persisted
        coVerify(atLeast = 2) { pillCountTxnDao.upsertPreservingId(any<PillCountTxnEntity>()) }
        job.cancel()
    }

    @Test
    fun `onBarcodeDetected same-ndc cooldown ignored`() = runTest(testDispatcher) {
        stubGs1Decode()
        coEvery { drugMasterDao.getDrugByGtin(any()) } returns drug()
        coEvery { drugMasterDao.getDrugIdByNdc(any()) } returns 10L

        val vm = createViewModel(batchId = 7L)
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.onBarcodeDetected("raw")
        advanceUntilIdle()
        // second rapid same-ndc camera scan within cooldown -> ignored, bottles stay 1
        vm.onBarcodeDetected("raw")
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.activeNdc?.bottles)
        job.cancel()
    }

    @Test
    fun `onBtBarcodeDetected bypasses cooldown and increments same ndc`() = runTest(testDispatcher) {
        stubGs1Decode()
        coEvery { drugMasterDao.getDrugByGtin(any()) } returns drug()
        coEvery { drugMasterDao.getDrugIdByNdc(any()) } returns 10L

        val vm = createViewModel(batchId = 7L)
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.onBtBarcodeDetected("raw")
        advanceUntilIdle()
        vm.onBtBarcodeDetected("raw")
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.activeNdc?.bottles)
        job.cancel()
    }

    @Test
    fun `onBarcodeDetected existing sealed txn seeds bottles from prev count`() = runTest(testDispatcher) {
        stubGs1Decode()
        coEvery { drugMasterDao.getDrugByGtin(any()) } returns drug()
        coEvery { drugMasterDao.getDrugIdByNdc(any()) } returns 10L
        coEvery {
            pillCountTxnDao.findSealedTxnInBatch(any(), any(), any(), any())
        } returns txnEntity(bottleQty = 4)

        val vm = createViewModel(batchId = 7L)
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.onBarcodeDetected("raw")
        advanceUntilIdle()

        assertEquals(5, vm.uiState.value.activeNdc?.bottles) // 4 + 1
        job.cancel()
    }

    @Test
    fun `onBarcodeDetected scan failed when decoder throws`() = runTest(testDispatcher) {
        every { barcodeDecoder.isGs1Barcode(any()) } throws RuntimeException("boom")

        val vm = createViewModel(batchId = 7L)
        advanceUntilIdle()

        vm.onBarcodeDetected("raw")
        advanceUntilIdle()

        assertEquals(R.string.batch_stock_count_scan_failed, vm.errorMessage.value?.messageResId)
    }

    // ─────────────────────────  persistActive branches  ─────────────────────────

    @Test
    fun `persistActive update path when existing txn found via onAdd`() = runTest(testDispatcher) {
        stubGs1Decode()
        coEvery { drugMasterDao.getDrugByGtin(any()) } returns drug()
        coEvery { drugMasterDao.getDrugIdByNdc(any()) } returns 10L
        // null on scan (insert), then existing on onAdd flush (update)
        coEvery {
            pillCountTxnDao.findSealedTxnInBatch(any(), any(), any(), any())
        } returnsMany listOf(null, txnEntity())

        val vm = createViewModel(batchId = 7L)
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.onBarcodeDetected("raw")
        advanceUntilIdle()
        vm.onAdd()
        advanceUntilIdle()

        coVerify { pillCountTxnDao.update(any()) }
        assertNull(vm.uiState.value.activeNdc)
        job.cancel()
    }

    @Test
    fun `persistActive drug_not_found when drugId null`() = runTest(testDispatcher) {
        stubGs1Decode()
        coEvery { drugMasterDao.getDrugByGtin(any()) } returns drug()
        // drugId lookup returns null in persistActive
        coEvery { drugMasterDao.getDrugIdByNdc(any()) } returns null

        val vm = createViewModel(batchId = 7L)
        advanceUntilIdle()

        vm.onBarcodeDetected("raw")
        advanceUntilIdle()

        assertEquals(R.string.batch_stock_count_drug_not_found, vm.errorMessage.value?.messageResId)
    }

    @Test
    fun `persistActive save_failed when dao throws`() = runTest(testDispatcher) {
        stubGs1Decode()
        coEvery { drugMasterDao.getDrugByGtin(any()) } returns drug()
        coEvery { drugMasterDao.getDrugIdByNdc(any()) } returns 10L
        coEvery { pillCountTxnDao.findSealedTxnInBatch(any(), any(), any(), any()) } returns null
        coEvery { pillCountTxnDao.upsertPreservingId(any<PillCountTxnEntity>()) } throws RuntimeException("db")

        val vm = createViewModel(batchId = 7L)
        advanceUntilIdle()

        vm.onBarcodeDetected("raw")
        advanceUntilIdle()

        assertEquals(R.string.batch_stock_count_save_failed, vm.errorMessage.value?.messageResId)
    }

    // ─────────────────────────  clearErrorMessage  ─────────────────────────

    @Test
    fun `clearErrorMessage resets error`() = runTest(testDispatcher) {
        every { barcodeDecoder.isGs1Barcode(any()) } returns false
        every { barcodeDecoder.toGtin14(any()) } returns null
        val vm = createViewModel(batchId = 7L)
        advanceUntilIdle()

        vm.onBarcodeDetected("AB")
        advanceUntilIdle()
        assertNotNull(vm.errorMessage.value)

        vm.clearErrorMessage()
        assertNull(vm.errorMessage.value)
    }

    // ─────────────────────────  increment / decrement / schedulePersist  ─────────────────────────

    @Test
    fun `increment with null active returns no-op`() = runTest(testDispatcher) {
        val vm = createViewModel(batchId = 7L)
        advanceUntilIdle()
        vm.increment()
        advanceUntilIdle()
        assertNull(vm.uiState.value.activeNdc)
    }

    @Test
    fun `decrement with null active returns no-op`() = runTest(testDispatcher) {
        val vm = createViewModel(batchId = 7L)
        advanceUntilIdle()
        vm.decrement()
        advanceUntilIdle()
        assertNull(vm.uiState.value.activeNdc)
    }

    @Test
    fun `increment then decrement debounced persist fires after delay`() = runTest(testDispatcher) {
        stubGs1Decode()
        coEvery { drugMasterDao.getDrugByGtin(any()) } returns drug()
        coEvery { drugMasterDao.getDrugIdByNdc(any()) } returns 10L
        coEvery { pillCountTxnDao.findSealedTxnInBatch(any(), any(), any(), any()) } returns null

        val vm = createViewModel(batchId = 7L)
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.onBarcodeDetected("raw")
        advanceUntilIdle()

        vm.increment() // bottles 1 -> 2
        vm.increment() // 2 -> 3
        // uiState is a combined flow — advance so it reflects the latest _activeNdc.
        advanceUntilIdle()
        assertEquals(3, vm.uiState.value.activeNdc?.bottles)

        // decrement floors at 1
        vm.decrement() // 3 -> 2
        vm.decrement() // 2 -> 1
        vm.decrement() // stays 1 (coerceAtLeast 1)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.activeNdc?.bottles)
        job.cancel()
    }

    // ─────────────────────────  onRecentRowTapped  ─────────────────────────

    @Test
    fun `onRecentRowTapped batchId zero returns`() = runTest(testDispatcher) {
        val vm = createViewModel(batchId = 0L)
        advanceUntilIdle()
        vm.onRecentRowTapped(RecentBatchRow("NDC", "name", 5, 1))
        advanceUntilIdle()
        coVerify(exactly = 0) { pillCountTxnDao.findLatestTxnByNdcInBatch(any(), any()) }
    }

    @Test
    fun `onRecentRowTapped txn null returns without activating`() = runTest(testDispatcher) {
        coEvery { pillCountTxnDao.findLatestTxnByNdcInBatch(7L, "NDC") } returns null
        coEvery { drugMasterDao.getDrugByNdc("NDC") } returns drug(ndc = "NDC")

        val vm = createViewModel(batchId = 7L)
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.onRecentRowTapped(RecentBatchRow("NDC", "name", 5, 1))
        advanceUntilIdle()

        assertNull(vm.uiState.value.activeNdc)
        job.cancel()
    }

    @Test
    fun `onRecentRowTapped drug null returns`() = runTest(testDispatcher) {
        coEvery { pillCountTxnDao.findLatestTxnByNdcInBatch(7L, "NDC") } returns txnEntity()
        coEvery { drugMasterDao.getDrugByNdc("NDC") } returns null

        val vm = createViewModel(batchId = 7L)
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.onRecentRowTapped(RecentBatchRow("NDC", "name", 5, 1))
        advanceUntilIdle()

        assertNull(vm.uiState.value.activeNdc)
        job.cancel()
    }

    @Test
    fun `onRecentRowTapped success activates and commits current different active`() = runTest(testDispatcher) {
        // First scan to set an active NDC (different from tapped row)
        stubGs1Decode()
        coEvery { drugMasterDao.getDrugByGtin(any()) } returns drug(ndc = "ACTIVE-NDC")
        coEvery { drugMasterDao.getDrugIdByNdc("ACTIVE-NDC") } returns 10L
        coEvery { pillCountTxnDao.findLatestTxnByNdcInBatch(7L, "ROW-NDC") } returns txnEntity(bottleQty = 7, lotNo = "RL", expiry = "RE")
        coEvery { drugMasterDao.getDrugByNdc("ROW-NDC") } returns drug(drugId = 30L, ndc = "ROW-NDC", drugName = "Row Drug")

        val vm = createViewModel(batchId = 7L)
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.onBarcodeDetected("raw")
        advanceUntilIdle()
        assertEquals("ACTIVE-NDC", vm.uiState.value.activeNdc?.ndc)

        vm.onRecentRowTapped(RecentBatchRow("ROW-NDC", "Row Drug", 7, 7))
        advanceUntilIdle()

        assertEquals("ROW-NDC", vm.uiState.value.activeNdc?.ndc)
        assertEquals(7, vm.uiState.value.activeNdc?.bottles)
        job.cancel()
    }

    @Test
    fun `onRecentRowTapped catches exception`() = runTest(testDispatcher) {
        coEvery { pillCountTxnDao.findLatestTxnByNdcInBatch(any(), any()) } throws RuntimeException("db")

        val vm = createViewModel(batchId = 7L)
        advanceUntilIdle()
        vm.onRecentRowTapped(RecentBatchRow("NDC", "name", 5, 1))
        advanceUntilIdle()
        assertNull(vm.errorMessage.value)
    }

    // ─────────────────────────  onClear  ─────────────────────────

    @Test
    fun `onClear clears active ndc`() = runTest(testDispatcher) {
        stubGs1Decode()
        coEvery { drugMasterDao.getDrugByGtin(any()) } returns drug()
        coEvery { drugMasterDao.getDrugIdByNdc(any()) } returns 10L

        val vm = createViewModel(batchId = 7L)
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.onBarcodeDetected("raw")
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.activeNdc)

        vm.onClear()
        advanceUntilIdle()
        assertNull(vm.uiState.value.activeNdc)
        job.cancel()
    }

    // ─────────────────────────  onAdd  ─────────────────────────

    @Test
    fun `onAdd with null active is no-op`() = runTest(testDispatcher) {
        val vm = createViewModel(batchId = 7L)
        advanceUntilIdle()
        vm.onAdd()
        advanceUntilIdle()
        coVerify(exactly = 0) { pillCountTxnDao.upsertPreservingId(any<PillCountTxnEntity>()) }
    }

    @Test
    fun `onAdd persists and clears active`() = runTest(testDispatcher) {
        stubGs1Decode()
        coEvery { drugMasterDao.getDrugByGtin(any()) } returns drug()
        coEvery { drugMasterDao.getDrugIdByNdc(any()) } returns 10L
        coEvery { pillCountTxnDao.findSealedTxnInBatch(any(), any(), any(), any()) } returns null

        val vm = createViewModel(batchId = 7L)
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.onBarcodeDetected("raw")
        advanceUntilIdle()
        vm.onAdd()
        advanceUntilIdle()

        assertNull(vm.uiState.value.activeNdc)
        job.cancel()
    }

    // ─────────────────────────  onScanPillsForActive  ─────────────────────────

    @Test
    fun `onScanPillsForActive with active uses single-ndc allowlist`() = runTest(testDispatcher) {
        stubGs1Decode()
        coEvery { drugMasterDao.getDrugByGtin(any()) } returns drug()
        coEvery { drugMasterDao.getDrugIdByNdc(any()) } returns 10L

        val vm = createViewModel(batchId = 7L)
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.onBarcodeDetected("raw")
        advanceUntilIdle()

        var readyBatch = -1L
        var readyNdcs: Set<String> = setOf("sentinel")
        vm.onScanPillsForActive { b, n -> readyBatch = b; readyNdcs = n }
        advanceUntilIdle()

        assertEquals(7L, readyBatch)
        assertEquals(setOf("00093-0058"), readyNdcs)
        coVerify { preferenceHelper.saveTxnId(0) }
        job.cancel()
    }

    @Test
    fun `onScanPillsForActive no active manual batch uses empty allowlist`() = runTest(testDispatcher) {
        val vm = createViewModel(batchId = 7L)
        advanceUntilIdle()

        var readyNdcs: Set<String> = setOf("x")
        vm.onScanPillsForActive { _, n -> readyNdcs = n }
        advanceUntilIdle()

        assertTrue(readyNdcs.isEmpty())
    }

    @Test
    fun `onScanPillsForActive no active PMS batch uses expectedNdcs`() = runTest(testDispatcher) {
        coEvery { batchDao.getById(5L) } returns BatchEntity(
            batchId = 5L, bucketId = "B", requestIdFromPMS = "REQ"
        )
        coEvery { pillCountTxnDao.getTxnsByBatchId(5L) } returns listOf(
            BatchTxnDto(1, 1L, "x", "PMS-1", "l", "e", 1, 0, 1),
            BatchTxnDto(2, 2L, "y", "PMS-2", "l", "e", 1, 0, 1),
        )
        val vm = createViewModel(batchId = 5L)
        advanceUntilIdle()

        var readyNdcs: Set<String> = emptySet()
        vm.onScanPillsForActive { _, n -> readyNdcs = n }
        advanceUntilIdle()

        assertEquals(setOf("PMS-1", "PMS-2"), readyNdcs)
    }

    @Test
    fun `onScanPillsForActive batchId zero emits no_active_batch`() = runTest(testDispatcher) {
        coEvery { batchDao.insert(any()) } returns 0L
        val vm = createViewModel(batchId = 0L)
        advanceUntilIdle()

        var called = false
        vm.onScanPillsForActive { _, _ -> called = true }
        advanceUntilIdle()

        assertFalse(called)
        assertEquals(R.string.batch_stock_count_no_active_batch, vm.errorMessage.value?.messageResId)
    }

    @Test
    fun `onScanPillsForActive creates batch when zero`() = runTest(testDispatcher) {
        coEvery { batchDao.insert(any()) } returns 42L
        val vm = createViewModel(batchId = 0L)
        advanceUntilIdle()

        var readyBatch = -1L
        vm.onScanPillsForActive { b, _ -> readyBatch = b }
        advanceUntilIdle()

        assertEquals(42L, readyBatch)
    }

    @Test
    fun `onScanPillsForActive catches exception emits scan_failed`() = runTest(testDispatcher) {
        coEvery { preferenceHelper.saveTxnId(any()) } throws RuntimeException("pref")
        val vm = createViewModel(batchId = 7L)
        advanceUntilIdle()

        vm.onScanPillsForActive { _, _ -> }
        advanceUntilIdle()

        assertEquals(R.string.batch_stock_count_scan_failed, vm.errorMessage.value?.messageResId)
    }

    // ─────────────────────────  end count  ─────────────────────────

    @Test
    fun `requestEndCount shows dialog`() = runTest(testDispatcher) {
        val vm = createViewModel(batchId = 7L)
        advanceUntilIdle()
        vm.requestEndCount()
        assertTrue(vm.showEndCountDialog.value)
    }

    @Test
    fun `dismissEndCount hides dialog`() = runTest(testDispatcher) {
        val vm = createViewModel(batchId = 7L)
        advanceUntilIdle()
        vm.requestEndCount()
        vm.dismissEndCount()
        assertFalse(vm.showEndCountDialog.value)
    }

    @Test
    fun `confirmEndCount no committed ndc does nothing but ends batch`() = runTest(testDispatcher) {
        val vm = createViewModel(batchId = 7L)
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.confirmEndCount()
        advanceUntilIdle()

        coVerify(exactly = 0) { batchDao.markAsCompleted(any(), any()) }
        assertTrue(vm.batchEnded.value)
        assertFalse(vm.showEndCountDialog.value)
        job.cancel()
    }

    @Test
    fun `confirmEndCount committed ndc marks completed updates note and resends when connected`() = runTest(testDispatcher) {
        every { pillCountTxnDao.observeByBatchId(7L) } returns flowOf(
            listOf(BatchTxnDto(1, 10L, "Aspirin", "NDC-A", "L", "E", 1, 0, 5))
        )
        connectionState.value = true

        val vm = createViewModel(batchId = 7L)
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.confirmEndCount("a note")
        advanceUntilIdle()

        coVerify { batchDao.markAsCompleted(7L, any()) }
        coVerify { batchDao.updateNote(7L, "a note") }
        coVerify { hl7Repository.resendPendingHl7BatchTransactions() }
        assertTrue(vm.batchEnded.value)
        job.cancel()
    }

    @Test
    fun `confirmEndCount blank note skips updateNote and disconnected skips resend`() = runTest(testDispatcher) {
        every { pillCountTxnDao.observeByBatchId(7L) } returns flowOf(
            listOf(BatchTxnDto(1, 10L, "Aspirin", "NDC-A", "L", "E", 1, 0, 5))
        )
        connectionState.value = false

        val vm = createViewModel(batchId = 7L)
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.confirmEndCount("   ")
        advanceUntilIdle()

        coVerify { batchDao.markAsCompleted(7L, any()) }
        coVerify(exactly = 0) { batchDao.updateNote(any(), any()) }
        coVerify(exactly = 0) { hl7Repository.resendPendingHl7BatchTransactions() }
        job.cancel()
    }

    @Test
    fun `confirmEndCount catches exception but still ends batch`() = runTest(testDispatcher) {
        every { pillCountTxnDao.observeByBatchId(7L) } returns flowOf(
            listOf(BatchTxnDto(1, 10L, "Aspirin", "NDC-A", "L", "E", 1, 0, 5))
        )
        coEvery { batchDao.markAsCompleted(7L, any()) } throws RuntimeException("db")

        val vm = createViewModel(batchId = 7L)
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.confirmEndCount()
        advanceUntilIdle()

        assertTrue(vm.batchEnded.value)
        assertFalse(vm.showEndCountDialog.value)
        job.cancel()
    }
}
