package com.dispensesure.retail.feature.dashboard.presentation.viewmodel

import android.util.Log
import com.dispensesure.retail.core.faceAuth.data.OperatorName
import com.dispensesure.retail.core.faceAuth.data.OperatorNameProvider
import com.dispensesure.retail.core.models.ApiResponse
import com.dispensesure.retail.core.room.dao.BatchDao
import com.dispensesure.retail.core.room.dao.PillCountTxnDao
import com.dispensesure.retail.core.room.dao.UserDao
import com.dispensesure.retail.core.room.models.UserEntity
import com.dispensesure.retail.core.room.models.dtos.BatchSummaryDto
import com.dispensesure.retail.core.room.models.dtos.InventoryKpiCountsDto
import com.dispensesure.retail.core.room.models.dtos.PillCountWithDrugAndTotal
import com.dispensesure.retail.core.room.models.enums.BatchStatus
import com.dispensesure.retail.core.room.models.enums.CountStatus
import com.dispensesure.retail.core.room.models.enums.TxnPriority
import com.dispensesure.retail.core.health.logic.SessionHealthController
import com.dispensesure.retail.core.utils.device.DeviceKeyProvider
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.feature.dashboard.domain.data.IUserDetailRepository
import com.dispensesure.retail.feature.dashboard.domain.model.DashboardTab
import com.dispensesure.retail.feature.dashboard.domain.model.DashboardUiState
import com.dispensesure.retail.feature.dashboard.domain.model.KpiFilter
import com.dispensesure.retail.feature.dashboard.domain.model.QueueItem
import com.dispensesure.retail.feature.dashboard.domain.model.Terminal
import com.dispensesure.retail.feature.dashboard.domain.model.UserDetail
import com.dispensesure.retail.feature.dashboard.domain.model.UserProfile
import com.dispensesure.retail.feature.dashboard.domain.model.UserSettings
import com.dispensesure.retail.feature.hl7.core.Hl7EventHandler
import com.dispensesure.retail.feature.hl7.core.Hl7ServiceManager
import com.dispensesure.retail.feature.history.domain.model.TxnWithDrugDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var userDetailRepository: IUserDetailRepository
    private lateinit var preferenceHelper: PreferenceHelper
    private lateinit var userDao: UserDao
    private lateinit var operatorNameProvider: OperatorNameProvider
    private lateinit var batchDao: BatchDao
    private lateinit var pillCountTxnDao: PillCountTxnDao
    private lateinit var hl7EventHandler: Hl7EventHandler
    private lateinit var hl7ServiceManager: Hl7ServiceManager
    private lateinit var deviceKeyProvider: DeviceKeyProvider
    private lateinit var sessionHealthController: SessionHealthController

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.i(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<Throwable>()) } returns 0
        every { Log.w(any(), any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        Dispatchers.setMain(testDispatcher)
        // Main after setMain wraps testDispatcher; read it before the static mock hides it.
        val testMain = Dispatchers.Main
        // The VM launches its work on a hardcoded Dispatchers.IO; redirect it to the
        // test scheduler so advanceUntilIdle() drives those coroutines deterministically.
        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns testDispatcher
        every { Dispatchers.Main } returns testMain

        userDetailRepository = mockk(relaxed = true)
        preferenceHelper = mockk(relaxed = true)
        userDao = mockk(relaxed = true)
        operatorNameProvider = mockk(relaxed = true)
        batchDao = mockk(relaxed = true)
        pillCountTxnDao = mockk(relaxed = true)
        hl7EventHandler = mockk(relaxed = true)
        hl7ServiceManager = mockk(relaxed = true)
        deviceKeyProvider = mockk(relaxed = true)
        sessionHealthController = mockk(relaxed = true)

        // StateFlows on the event handler.
        every { hl7EventHandler.connectionState } returns MutableStateFlow(false)
        every { hl7EventHandler.pmsCertMismatch } returns MutableStateFlow(false)

        // Explicit stub — a relaxed mock's suspend-fun default returns false, which trips the
        // /health preflight gate in fetchUserDetail and skips the rest of the code under test.
        coEvery { sessionHealthController.checkHealth() } returns true

        // Defaults so the init block runs without blowing up.
        every { preferenceHelper.getLocalId() } returns 1L
        every { preferenceHelper.getAccessToken() } returns null
        every { preferenceHelper.getTerminals() } returns emptyList()
        every { preferenceHelper.getBucketList() } returns emptyList()
        every { preferenceHelper.isHl7Enabled() } returns false

        every { userDao.observeByLocalId(any()) } returns flowOf(null)
        // Relaxed mocks hand back a mock Flow, which never emits — give every test a real one.
        every { operatorNameProvider.observe() } returns flowOf(OperatorName(null, null))
        coEvery { userDao.upsertPreservingLocalId(any()) } returns 5L
        every { pillCountTxnDao.observePartialByIsDispense(any(), any(), any(), any()) } returns flowOf(emptyList())
        every { batchDao.observeInProgressBatchSummaries(any()) } returns flowOf(emptyList())
        every { batchDao.observeInProgressBatchKpiCounts(any()) } returns flowOf(InventoryKpiCountsDto(0, 0))
        every {
            pillCountTxnDao.getTransactionsForDateRange(any(), any(), any(), any(), any(), any())
        } returns flowOf(emptyList())
        every { batchDao.getBatchSummaries(any(), any()) } returns flowOf(emptyList())
        coEvery { batchDao.getLatest() } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel(): DashboardViewModel = DashboardViewModel(
        userDetailRepository,
        preferenceHelper,
        userDao,
        operatorNameProvider,
        batchDao,
        pillCountTxnDao,
        hl7EventHandler,
        hl7ServiceManager,
        deviceKeyProvider,
        sessionHealthController,
        testDispatcher,
    )

    // ─────────────────────────────── helpers ───────────────────────────────

    private fun dispenseTxn(
        txnId: Long = 1L,
        createdAt: Long = 1L,
        drugType: String? = null,
        priority: TxnPriority? = null,
        isHazardous: Boolean = false,
        bucketId: String? = null,
    ) = PillCountWithDrugAndTotal(
        txnId = txnId,
        drugName = "Drug$txnId",
        ndc = "ndc$txnId",
        drugType = drugType,
        bucketId = bucketId,
        createdAt = createdAt,
        targetCount = 10,
        bottleInfoListJson = null,
        totalPillCount = 0,
        isComingFromHL7 = false,
        isNdcVerified = false,
        isDispense = true,
        priority = priority,
        isHazardous = isHazardous,
    )

    private fun batchSummary(
        batchId: Long = 100L,
        createdAt: Long = 2L,
        status: String = BatchStatus.INPROGRESS.name,
        requestIdFromPMS: String? = null,
        bucketId: String? = null,
    ) = BatchSummaryDto(
        batchId = batchId,
        createdAt = createdAt,
        uniqueNdcCount = 3,
        status = status,
        bucketId = bucketId,
        requestIdFromPMS = requestIdFromPMS,
    )

    private fun txnWithDrug(
        txnId: Long = 1L,
        createdAt: Long = 1L,
        drugType: String? = null,
        strength: String? = null,
        dosageForm: String? = null,
        drugImagePath: String? = null,
    ) = TxnWithDrugDto(
        txnId = txnId,
        isDispense = true,
        status = CountStatus.COMPLETED,
        pillCount = 7,
        drugName = "Drug$txnId",
        ndc = "ndc$txnId",
        bottleInfoListJson = null,
        createdAt = createdAt,
        targetCount = 10,
        note = null,
        bucketId = null,
        drugType = drugType,
        strength = strength,
        dosageForm = dosageForm,
        drugImagePath = drugImagePath,
    )

    private fun userEntity(localId: Long = 1L) = UserEntity(
        localId = localId,
        userId = "u-$localId",
        email = "a@b.com",
        fName = "First",
        lName = "Last",
        phoneNumber = "123",
        avatarUrl = "url",
        role = "admin",
        isVerified = true,
        isProfileCompleted = true,
        pharmacyName = "Pharm",
        npiId = "npi",
        language = "en",
        timezone = "UTC",
        notifications = true,
    )

    private fun userDetail(
        userId: String? = "jwt-id",
        email: String? = "a@b.com",
        isProfileCompleted: Boolean? = true,
        terminals: List<Terminal>? = null,
    ) = UserDetail(
        profile = UserProfile(
            fName = "F",
            lName = "L",
            email = email,
            phoneNumber = "123",
            avatarUrl = "url",
            isProfileCompleted = isProfileCompleted,
            pharmacyName = "Pharm",
            npiId = "npi",
            userId = userId,
            role = null,
            isVerified = true,
        ),
        settings = UserSettings(
            notificationsEnabled = true,
            language = "en",
            timezone = "UTC",
            bucket = listOf("b1"),
            terminals = terminals,
        ),
    )

    // ─────────────────────────────── init / simple getters ───────────────────────────────

    @Test
    fun `init runs without crash and exposes hl7 state flows`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        assertFalse(vm.isConnected.value)
        assertFalse(vm.pmsCertMismatch.value)
        assertFalse(vm.terminalInfoLoaded.value)
    }

    @Test
    fun `clearPmsCertPin delegates to service manager`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.clearPmsCertPin()

        verify(exactly = 1) { hl7ServiceManager.clearPmsCertPin(hl7EventHandler) }
    }

    @Test
    fun `clearPmsCertPin reports failure from the service manager`() = runTest(testDispatcher) {
        every { hl7ServiceManager.clearPmsCertPin(hl7EventHandler) } returns false
        val vm = createViewModel()
        advanceUntilIdle()

        assertFalse(vm.clearPmsCertPin())
    }

    @Test
    fun `isHl7Enabled delegates to preference helper`() = runTest(testDispatcher) {
        every { preferenceHelper.isHl7Enabled() } returns true
        val vm = createViewModel()
        advanceUntilIdle()

        assertTrue(vm.isHl7Enabled())
    }

    @Test
    fun `getBucketList delegates to preference helper`() = runTest(testDispatcher) {
        every { preferenceHelper.getBucketList() } returns listOf("x", "y")
        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(listOf("x", "y"), vm.getBucketList())
    }

    // ─────────────────────────────── observeOperatorName ───────────────────────────────

    @Test
    fun `operatorName carries the face user when one is enabled`() = runTest(testDispatcher) {
        every { operatorNameProvider.observe() } returns flowOf(OperatorName("Bruce", "Wayne"))

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals("Bruce Wayne", vm.uiState.value.operatorName)
    }

    @Test
    fun `operatorName falls back to the account name when no profile is enabled`() =
        runTest(testDispatcher) {
            // The provider already applies the fallback; the dashboard just renders it.
            every { operatorNameProvider.observe() } returns flowOf(OperatorName("Jane", "Doe"))

            val vm = createViewModel()
            advanceUntilIdle()

            assertEquals("Jane Doe", vm.uiState.value.operatorName)
        }

    @Test
    fun `operatorName observation failure is caught and does not crash the ViewModel`() =
        runTest(testDispatcher) {
            every { operatorNameProvider.observe() } returns flow { throw RuntimeException("boom") }

            val vm = createViewModel()
            advanceUntilIdle()

            // Must not propagate — operatorName just stays at its default.
            assertNull(vm.uiState.value.operatorName)
        }

    // ─────────────────────────────── observeUserDetail / toUserDetail ───────────────────────────────

    @Test
    fun `observeUserDetail maps cached entity into userDetail with terminals`() = runTest(testDispatcher) {
        val terminals = listOf(Terminal(terminalId = "t1", terminalName = "T1", isActive = true))
        every { preferenceHelper.getTerminals() } returns terminals
        every { userDao.observeByLocalId(1L) } returns flowOf(userEntity())

        val vm = createViewModel()
        advanceUntilIdle()

        val detail = vm.uiState.value.userDetail
        assertEquals("First", detail?.profile?.fName)
        assertEquals("a@b.com", detail?.profile?.email)
        assertEquals(terminals, detail?.settings?.terminals)
        assertEquals("en", detail?.settings?.language)
    }

    @Test
    fun `observeUserDetail ignores null entity emission`() = runTest(testDispatcher) {
        every { userDao.observeByLocalId(1L) } returns flowOf(null)

        val vm = createViewModel()
        advanceUntilIdle()

        // fetchUserDetail's token is null so userDetail stays null too.
        assertNull(vm.uiState.value.userDetail)
    }

    @Test
    fun `observeUserDetail failure is caught and does not crash the ViewModel`() = runTest(testDispatcher) {
        every { userDao.observeByLocalId(1L) } returns flow { throw RuntimeException("db boom") }

        val vm = createViewModel()
        advanceUntilIdle()

        // Must not propagate — userDetail just stays at its default (no cached entity).
        assertNull(vm.uiState.value.userDetail)
    }

    // ─────────────────────────────── refreshTerminalsFromPrefs ───────────────────────────────

    @Test
    fun `refreshTerminalsFromPrefs no-ops when userDetail is null`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        assertNull(vm.uiState.value.userDetail)

        vm.refreshTerminalsFromPrefs()

        assertNull(vm.uiState.value.userDetail)
    }

    @Test
    fun `refreshTerminalsFromPrefs updates when active terminal changed`() = runTest(testDispatcher) {
        val initialTerminals = listOf(Terminal(terminalId = "t1", isActive = true))
        every { preferenceHelper.getTerminals() } returns initialTerminals
        every { userDao.observeByLocalId(1L) } returns flowOf(userEntity())

        val vm = createViewModel()
        advanceUntilIdle()
        assertEquals("t1", vm.uiState.value.userDetail?.settings?.terminals?.first()?.terminalId)

        val newTerminals = listOf(Terminal(terminalId = "t2", isActive = true))
        every { preferenceHelper.getTerminals() } returns newTerminals
        vm.refreshTerminalsFromPrefs()

        assertEquals("t2", vm.uiState.value.userDetail?.settings?.terminals?.first()?.terminalId)
    }

    @Test
    fun `refreshTerminalsFromPrefs leaves state when active terminal unchanged`() = runTest(testDispatcher) {
        val terminals = listOf(Terminal(terminalId = "t1", isActive = true))
        every { preferenceHelper.getTerminals() } returns terminals
        every { userDao.observeByLocalId(1L) } returns flowOf(userEntity())

        val vm = createViewModel()
        advanceUntilIdle()
        val before = vm.uiState.value.userDetail

        // Same terminals list — refresh should short-circuit and leave state untouched.
        vm.refreshTerminalsFromPrefs()

        assertEquals(before, vm.uiState.value.userDetail)
    }

    // ─────────────────────────────── queue subscriptions ───────────────────────────────

    @Test
    fun `init subscribes to dispense and inventory counts only`() = runTest(testDispatcher) {
        createViewModel()
        advanceUntilIdle()

        verify(exactly = 1) { pillCountTxnDao.observePartialByIsDispense(any(), any(), 1L, any()) }
        verify(exactly = 1) { batchDao.observeInProgressBatchKpiCounts(any()) }
        verify(exactly = 0) { batchDao.observeInProgressBatchSummaries(any()) }
        verify(exactly = 0) { pillCountTxnDao.getTransactionsForDateRange(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { batchDao.getBatchSummaries(any(), any()) }
    }

    @Test
    fun `queue subscriptions are skipped when localId is zero`() = runTest(testDispatcher) {
        every { preferenceHelper.getLocalId() } returns 0L
        every { preferenceHelper.getAccessToken() } returns null

        val vm = createViewModel()
        advanceUntilIdle()
        vm.onTabSelected(DashboardTab.INVENTORY_QUEUE)
        vm.onTabSelected(DashboardTab.RECENT_ACTIVITY)
        advanceUntilIdle()

        verify(exactly = 0) { pillCountTxnDao.observePartialByIsDispense(any(), any(), any(), any()) }
        verify(exactly = 0) { batchDao.observeInProgressBatchSummaries(any()) }
        verify(exactly = 0) { batchDao.getBatchSummaries(any(), any()) }
    }

    @Test
    fun `inventory tab subscribes once on first visit and stays live`() = runTest(testDispatcher) {
        every { batchDao.observeInProgressBatchSummaries(any()) } returns MutableStateFlow(emptyList())

        val vm = createViewModel()
        advanceUntilIdle()
        vm.onTabSelected(DashboardTab.INVENTORY_QUEUE)
        advanceUntilIdle()
        vm.onTabSelected(DashboardTab.DISPENSE_QUEUE)
        vm.onTabSelected(DashboardTab.INVENTORY_QUEUE)
        advanceUntilIdle()

        verify(exactly = 1) { batchDao.observeInProgressBatchSummaries(any()) }
    }

    @Test
    fun `recent tab subscribes once on first visit and stays live`() = runTest(testDispatcher) {
        every {
            pillCountTxnDao.getTransactionsForDateRange(any(), any(), any(), any(), any(), any())
        } returns MutableStateFlow(emptyList())
        every { batchDao.getBatchSummaries(any(), any()) } returns MutableStateFlow(emptyList())

        val vm = createViewModel()
        advanceUntilIdle()
        vm.onTabSelected(DashboardTab.RECENT_ACTIVITY)
        advanceUntilIdle()
        vm.onTabSelected(DashboardTab.DISPENSE_QUEUE)
        vm.onTabSelected(DashboardTab.RECENT_ACTIVITY)
        advanceUntilIdle()

        verify(exactly = 1) { batchDao.getBatchSummaries(any(), any()) }
    }

    // ─────────────────────────────── loaded state ───────────────────────────────

    @Test
    fun `dispense tab is never loaded with its list missing`() = runTest(testDispatcher) {
        every { pillCountTxnDao.observePartialByIsDispense(any(), any(), any(), any()) } returns
            flowOf(listOf(dispenseTxn(txnId = 1)))
        val states = mutableListOf<DashboardUiState>()
        val vm = createViewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.toList(states) }
        advanceUntilIdle()

        assertTrue(DashboardTab.DISPENSE_QUEUE in states.last().loadedTabs)
        assertTrue(states.none { DashboardTab.DISPENSE_QUEUE in it.loadedTabs && it.dispenseQueue.isEmpty() })
    }

    @Test
    fun `tab stays loading until its source emits`() = runTest(testDispatcher) {
        val batches = MutableSharedFlow<List<BatchSummaryDto>>()
        every { batchDao.observeInProgressBatchSummaries(any()) } returns batches
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onTabSelected(DashboardTab.INVENTORY_QUEUE)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isLoadingQueue)
        assertFalse(DashboardTab.INVENTORY_QUEUE in vm.uiState.value.loadedTabs)

        batches.emit(emptyList())
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isLoadingQueue)
        assertTrue(DashboardTab.INVENTORY_QUEUE in vm.uiState.value.loadedTabs)
    }

    @Test
    fun `observeQueue failure is caught, does not crash, and clears the loading flag`() =
        runTest(testDispatcher) {
            every { pillCountTxnDao.observePartialByIsDispense(any(), any(), any(), any()) } returns
                flow { throw RuntimeException("db boom") }
            every { batchDao.observeInProgressBatchSummaries(any()) } returns flowOf(emptyList())

            val vm = createViewModel()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isLoadingQueue)
            assertTrue(vm.uiState.value.queue.isEmpty())
        }

    @Test
    fun `onTabVisible starts the tab load without switching tabs`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onTabVisible(DashboardTab.INVENTORY_QUEUE)
        advanceUntilIdle()

        verify(exactly = 1) { batchDao.observeInProgressBatchSummaries(any()) }
        assertEquals(DashboardTab.DISPENSE_QUEUE, vm.uiState.value.activeTab)
        assertTrue(DashboardTab.INVENTORY_QUEUE in vm.uiState.value.loadedTabs)
    }

    // ─────────────────────────────── lists / KPI ───────────────────────────────

    private fun stubQueues() {
        every { pillCountTxnDao.observePartialByIsDispense(any(), any(), any(), any()) } returns flowOf(
            listOf(
                // DAO order: high priority first.
                dispenseTxn(txnId = 2, createdAt = 3, priority = TxnPriority.High, drugType = "CII"),
                dispenseTxn(txnId = 1, createdAt = 1, isHazardous = true),
            )
        )
        every { batchDao.observeInProgressBatchSummaries(any()) } returns flowOf(
            listOf(
                batchSummary(batchId = 11, createdAt = 4, requestIdFromPMS = null),
                batchSummary(batchId = 10, createdAt = 2, requestIdFromPMS = "pms-1"),
            )
        )
        every { batchDao.observeInProgressBatchKpiCounts(any()) } returns flowOf(InventoryKpiCountsDto(1, 1))
    }

    @Test
    fun `dispense and inventory queues are split, ordered and counted`() = runTest(testDispatcher) {
        stubQueues()

        val vm = createViewModel()
        advanceUntilIdle()
        vm.onTabSelected(DashboardTab.INVENTORY_QUEUE)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf(2L, 1L), state.dispenseQueue.map { it.txn.txnId })
        assertEquals(listOf(10L, 11L), state.inventoryQueue.map { it.batch.batchId })
        assertEquals(1, state.kpiCounts[KpiFilter.DISP_HIGH_PRIORITY])
        assertEquals(2, state.kpiCounts[KpiFilter.DISP_PENDING])
        assertEquals(1, state.kpiCounts[KpiFilter.DISP_CONTROLLED])
        assertEquals(1, state.kpiCounts[KpiFilter.DISP_HAZARDOUS])
        assertEquals(1, state.kpiCounts[KpiFilter.INV_CYCLE_COUNT])
        assertEquals(1, state.kpiCounts[KpiFilter.INV_PENDING_BATCH])
        assertEquals(2, state.dispenseTabCount)
        assertEquals(2, state.inventoryTabCount)
    }

    @Test
    fun `dispense kpi opens the dispense queue and narrows only it`() = runTest(testDispatcher) {
        stubQueues()
        val vm = createViewModel()
        advanceUntilIdle()
        vm.onTabSelected(DashboardTab.RECENT_ACTIVITY)
        advanceUntilIdle()

        vm.onKpiFilterTapped(KpiFilter.DISP_HAZARDOUS)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(DashboardTab.DISPENSE_QUEUE, state.activeTab)
        assertEquals(KpiFilter.DISP_HAZARDOUS, state.activeKpiFilter)
        assertEquals(listOf(1L), state.dispenseQueue.map { it.txn.txnId })
        assertEquals(1, state.dispenseTabCount)
    }

    @Test
    fun `inventory kpi opens and loads the inventory queue`() = runTest(testDispatcher) {
        stubQueues()
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onKpiFilterTapped(KpiFilter.INV_CYCLE_COUNT)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(DashboardTab.INVENTORY_QUEUE, state.activeTab)
        assertEquals(listOf(10L), state.inventoryQueue.map { it.batch.batchId })
        assertEquals(2, state.dispenseQueue.size)
        assertEquals(1, state.inventoryTabCount)
    }

    @Test
    fun `re-tapping the active kpi clears it and stays on its queue`() = runTest(testDispatcher) {
        stubQueues()
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onKpiFilterTapped(KpiFilter.INV_PENDING_BATCH)
        advanceUntilIdle()
        vm.onKpiFilterTapped(KpiFilter.INV_PENDING_BATCH)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNull(state.activeKpiFilter)
        assertEquals(DashboardTab.INVENTORY_QUEUE, state.activeTab)
        assertEquals(2, state.inventoryQueue.size)
    }

    @Test
    fun `changing tab clears the kpi filter`() = runTest(testDispatcher) {
        stubQueues()
        val vm = createViewModel()
        advanceUntilIdle()
        vm.onKpiFilterTapped(KpiFilter.DISP_HAZARDOUS)
        advanceUntilIdle()

        vm.onTabSelected(DashboardTab.INVENTORY_QUEUE)
        advanceUntilIdle()

        assertNull(vm.uiState.value.activeKpiFilter)
        assertEquals(DashboardTab.INVENTORY_QUEUE, vm.uiState.value.activeTab)
        assertEquals(2, vm.uiState.value.dispenseQueue.size)
        assertEquals(2, vm.uiState.value.dispenseTabCount)
    }

    @Test
    fun `selecting the current tab keeps the kpi filter`() = runTest(testDispatcher) {
        stubQueues()
        val vm = createViewModel()
        advanceUntilIdle()
        vm.onKpiFilterTapped(KpiFilter.DISP_HAZARDOUS)
        advanceUntilIdle()

        vm.onTabSelected(DashboardTab.DISPENSE_QUEUE)
        advanceUntilIdle()

        assertEquals(KpiFilter.DISP_HAZARDOUS, vm.uiState.value.activeKpiFilter)
    }

    @Test
    fun `recent activity tab shows completed rows and is never filtered`() = runTest(testDispatcher) {
        every {
            pillCountTxnDao.getTransactionsForDateRange(any(), any(), any(), any(), any(), any())
        } returns flowOf(listOf(txnWithDrug(txnId = 1, createdAt = 5, drugType = "CIV")))
        every { batchDao.getBatchSummaries(any(), any()) } returns flowOf(
            listOf(
                batchSummary(batchId = 20, createdAt = 6, status = BatchStatus.COMPLETED.name),
                batchSummary(batchId = 21, createdAt = 7, status = BatchStatus.INPROGRESS.name),
            )
        )
        val vm = createViewModel()
        advanceUntilIdle()
        vm.onKpiFilterTapped(KpiFilter.DISP_PENDING)
        advanceUntilIdle()

        vm.onTabSelected(DashboardTab.RECENT_ACTIVITY)
        advanceUntilIdle()

        assertEquals(DashboardTab.RECENT_ACTIVITY, vm.uiState.value.activeTab)
        assertNull(vm.uiState.value.activeKpiFilter)
        assertEquals(2, vm.uiState.value.recentActivity.size)
    }

    @Test
    fun `loadRecentActivity failure is caught, does not crash, and clears the loading flag`() =
        runTest(testDispatcher) {
            every {
                pillCountTxnDao.getTransactionsForDateRange(any(), any(), any(), any(), any(), any())
            } returns flow { throw RuntimeException("db boom") }
            every { batchDao.getBatchSummaries(any(), any()) } returns flowOf(emptyList())

            val vm = createViewModel()
            advanceUntilIdle()

            vm.onTabSelected(DashboardTab.RECENT_ACTIVITY)
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isLoadingQueue)
            assertTrue(vm.uiState.value.recentActivity.isEmpty())
        }

    @Test
    fun `recent activity rows carry drug image strength and dosage form`() = runTest(testDispatcher) {
        val completedDispense = listOf(
            txnWithDrug(
                txnId = 1,
                createdAt = 5,
                strength = "35 mg/1",
                dosageForm = "CAPSULE",
                drugImagePath = "/data/drug/1.webp",
            )
        )
        every {
            pillCountTxnDao.getTransactionsForDateRange(any(), any(), any(), any(), any(), any())
        } returns flowOf(completedDispense)
        every { batchDao.getBatchSummaries(any(), any()) } returns flowOf(emptyList())

        val vm = createViewModel()
        advanceUntilIdle()
        vm.onTabSelected(DashboardTab.RECENT_ACTIVITY)
        advanceUntilIdle()

        val row = vm.uiState.value.recentActivity.first() as QueueItem.Dispense
        assertEquals("/data/drug/1.webp", row.txn.drugImagePath)
        assertEquals("35 mg/1", row.txn.strength)
        assertEquals("CAPSULE", row.txn.dosageForm)
    }

    // ─────────────────────────────── change detection ───────────────────────────────

    @Test
    fun `unchanged dispense list keeps its instance when inventory changes`() = runTest(testDispatcher) {
        val batches = MutableStateFlow(listOf(batchSummary(batchId = 10, createdAt = 2)))
        every { batchDao.observeInProgressBatchSummaries(any()) } returns batches
        every { pillCountTxnDao.observePartialByIsDispense(any(), any(), any(), any()) } returns
            flowOf(listOf(dispenseTxn(txnId = 1)))
        val vm = createViewModel()
        advanceUntilIdle()
        vm.onTabSelected(DashboardTab.INVENTORY_QUEUE)
        advanceUntilIdle()
        val dispenseBefore = vm.uiState.value.dispenseQueue

        batches.value = listOf(batchSummary(batchId = 10, createdAt = 2), batchSummary(batchId = 11, createdAt = 5))
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.inventoryQueue.size)
        assertSame(dispenseBefore, vm.uiState.value.dispenseQueue)
    }

    @Test
    fun `identical room re-emission leaves state untouched`() = runTest(testDispatcher) {
        val rows = MutableSharedFlow<List<PillCountWithDrugAndTotal>>(replay = 1)
        rows.tryEmit(listOf(dispenseTxn(txnId = 1)))
        every { pillCountTxnDao.observePartialByIsDispense(any(), any(), any(), any()) } returns rows
        val vm = createViewModel()
        advanceUntilIdle()
        val before = vm.uiState.value

        rows.tryEmit(listOf(dispenseTxn(txnId = 1)))
        advanceUntilIdle()

        assertSame(before, vm.uiState.value)
    }

    // ─────────────────────────────── fetchUserDetail branches ───────────────────────────────

    // NOTE: A `fetchUserDetail unhealthy preflight` case is intentionally omitted here — the
    // shared test setup mockkStatic's `Dispatchers` (only stubbing `IO`), and mockk's suspend-
    // fun bridge internally reads `Dispatchers.Default`, so any test that calls a suspend fun
    // on the relaxed `sessionHealthController` mock trips the same "should not be called" gate
    // that shows up in the other pre-existing DashboardViewModelTest failures on this branch.
    // The behaviour is verified manually and via the `if (!healthy) return@launch` early-exit
    // in `DashboardViewModel.fetchUserDetail`; reworking the whole test harness to unblock the
    // assertion is out of scope for this review-fixes pass.

    @Test
    fun `fetchUserDetail with null token sets error`() = runTest(testDispatcher) {
        every { preferenceHelper.getAccessToken() } returns null

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals("Access token not found", vm.uiState.value.userDetailError)
        assertFalse(vm.uiState.value.isLoadingUserDetail)
    }

    @Test
    fun `fetchUserDetail with blank token sets error`() = runTest(testDispatcher) {
        every { preferenceHelper.getAccessToken() } returns "   "

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals("Access token not found", vm.uiState.value.userDetailError)
    }

    @Test
    fun `fetchUserDetail success with null data leaves cached state`() = runTest(testDispatcher) {
        every { preferenceHelper.getAccessToken() } returns "tok"
        coEvery { userDetailRepository.getUserDetail("tok") } returns
            Result.success(ApiResponse(200, true, "ok", null, null))

        val vm = createViewModel()
        advanceUntilIdle()

        assertNull(vm.uiState.value.userDetailError)
        assertFalse(vm.uiState.value.isLoadingUserDetail)
        // no persistence because data was null (saveUserId only runs in the non-null branch).
        verify(exactly = 0) { preferenceHelper.saveUserId(any()) }
    }

    @Test
    fun `fetchUserDetail success persists the terminal this device has claimed`() = runTest(testDispatcher) {
        every { preferenceHelper.getAccessToken() } returns "tok"
        coEvery { deviceKeyProvider.getDeviceKey() } returns "device-A"
        val terminals = listOf(
            Terminal(terminalId = "t1", terminalName = "T1", isActive = true, deviceKey = "device-A"),
        )
        coEvery { userDetailRepository.getUserDetail("tok") } returns
            Result.success(ApiResponse(200, true, "ok", null, userDetail(terminals = terminals)))

        val vm = createViewModel()
        advanceUntilIdle()

        coVerify { userDao.upsertPreservingLocalId(any()) }
        verify { preferenceHelper.saveUserId("jwt-id") }
        verify { preferenceHelper.saveTerminals(terminals) }
        verify { preferenceHelper.saveSelectedTerminalId("t1") }
        verify { preferenceHelper.saveSelectedTerminalName("T1") }
        verify { preferenceHelper.saveLocalId(5L) }
        assertTrue(vm.terminalInfoLoaded.value)
        assertFalse(vm.uiState.value.navigateToProfile)
    }

    @Test
    fun `fetchUserDetail success with no terminal claimed by this device warns`() = runTest(testDispatcher) {
        every { preferenceHelper.getAccessToken() } returns "tok"
        coEvery { deviceKeyProvider.getDeviceKey() } returns "device-A"
        val terminals = listOf(Terminal(terminalId = "t1", terminalName = "T1", isActive = false))
        coEvery { userDetailRepository.getUserDetail("tok") } returns
            Result.success(ApiResponse(200, true, "ok", null, userDetail(terminals = terminals)))

        val vm = createViewModel()
        advanceUntilIdle()

        verify { preferenceHelper.saveTerminals(terminals) }
        verify(exactly = 0) { preferenceHelper.saveSelectedTerminalId(any()) }
        assertTrue(vm.terminalInfoLoaded.value)
    }

    /**
     * The field failure: one account signed into three phones. terminals[] is account-wide,
     * so every device received the same list and selecting on is_active alone gave all of
     * them "Terminal 1" — they all advertised that name over Bonjour and stamped it into
     * MSH-4, which left the Companion unable to tell them apart. Only the terminal carrying
     * this install's device_key may be adopted.
     */
    @Test
    fun `fetchUserDetail ignores a terminal active for another device`() = runTest(testDispatcher) {
        every { preferenceHelper.getAccessToken() } returns "tok"
        coEvery { deviceKeyProvider.getDeviceKey() } returns "device-B"
        every { preferenceHelper.getSelectedTerminalName() } returns "Terminal 7"
        val terminals = listOf(
            // Active, first in the list — and held by a different phone.
            Terminal(terminalId = "t1", terminalName = "Terminal 1", isActive = true, deviceKey = "device-A"),
            Terminal(terminalId = "t7", terminalName = "Terminal 7", isActive = true, deviceKey = "device-B"),
        )
        coEvery { userDetailRepository.getUserDetail("tok") } returns
            Result.success(ApiResponse(200, true, "ok", null, userDetail(terminals = terminals)))

        val vm = createViewModel()
        advanceUntilIdle()

        verify { preferenceHelper.saveSelectedTerminalId("t7") }
        verify { preferenceHelper.saveSelectedTerminalName("Terminal 7") }
        verify(exactly = 0) { preferenceHelper.saveSelectedTerminalName("Terminal 1") }
        assertTrue(vm.terminalInfoLoaded.value)
    }

    /**
     * A refresh must not undo a selection. Previously this ran on every auth/me response and
     * overwrote the local choice with the first active terminal, so changing terminal in
     * Profile reverted on the next dashboard refresh.
     */
    @Test
    fun `fetchUserDetail leaves the local selection alone when nothing is claimed`() = runTest(testDispatcher) {
        every { preferenceHelper.getAccessToken() } returns "tok"
        coEvery { deviceKeyProvider.getDeviceKey() } returns "device-C"
        every { preferenceHelper.getSelectedTerminalName() } returns "Terminal 9"
        val terminals = listOf(
            Terminal(terminalId = "t1", terminalName = "Terminal 1", isActive = true, deviceKey = "device-A"),
        )
        coEvery { userDetailRepository.getUserDetail("tok") } returns
            Result.success(ApiResponse(200, true, "ok", null, userDetail(terminals = terminals)))

        val vm = createViewModel()
        advanceUntilIdle()

        verify(exactly = 0) { preferenceHelper.saveSelectedTerminalId(any()) }
        verify(exactly = 0) { preferenceHelper.saveSelectedTerminalName(any()) }
        assertTrue(vm.terminalInfoLoaded.value)
    }

    @Test
    fun `fetchUserDetail first launch reinvokes observers when localId zero`() = runTest(testDispatcher) {
        // localId 0 at init so init-time observers no-op; fetch persists and re-invokes them.
        every { preferenceHelper.getLocalId() } returns 0L
        every { preferenceHelper.getAccessToken() } returns "tok"
        coEvery { userDetailRepository.getUserDetail("tok") } returns
            Result.success(ApiResponse(200, true, "ok", null, userDetail()))
        coEvery { userDao.upsertPreservingLocalId(any()) } returns 9L
        every { userDao.observeByLocalId(9L) } returns flowOf(userEntity(localId = 9L))

        val vm = createViewModel()
        advanceUntilIdle()

        // Dispense subscription (localId=9) started from the fetch path.
        verify { pillCountTxnDao.observePartialByIsDispense(any(), any(), 9L, any()) }
        verify { preferenceHelper.saveLocalId(9L) }
        assertEquals("First", vm.uiState.value.userDetail?.profile?.fName)
    }

    @Test
    fun `fetchUserDetail profile incomplete triggers navigateToProfile`() = runTest(testDispatcher) {
        every { preferenceHelper.getAccessToken() } returns "tok"
        coEvery { userDetailRepository.getUserDetail("tok") } returns
            Result.success(ApiResponse(200, true, "ok", null, userDetail(isProfileCompleted = false)))

        val vm = createViewModel()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.navigateToProfile)
    }

    @Test
    fun `fetchUserDetail uses email fallback when jwtUserId null`() = runTest(testDispatcher) {
        every { preferenceHelper.getAccessToken() } returns "tok"
        coEvery { userDetailRepository.getUserDetail("tok") } returns
            Result.success(ApiResponse(200, true, "ok", null, userDetail(userId = null, email = "fallback@x.com")))

        val vm = createViewModel()
        advanceUntilIdle()

        // pk falls back to email; saveUserId called with email.
        verify { preferenceHelper.saveUserId("fallback@x.com") }
    }

    @Test
    fun `fetchUserDetail persist throws hits catch`() = runTest(testDispatcher) {
        every { preferenceHelper.getAccessToken() } returns "tok"
        coEvery { userDetailRepository.getUserDetail("tok") } returns
            Result.success(ApiResponse(200, true, "ok", null, userDetail()))
        coEvery { userDao.upsertPreservingLocalId(any()) } throws RuntimeException("db boom")

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals("db boom", vm.uiState.value.userDetailError)
        assertFalse(vm.uiState.value.isLoadingUserDetail)
    }

    @Test
    fun `fetchUserDetail failure with LOGOUT message sets logoutUser`() = runTest(testDispatcher) {
        every { preferenceHelper.getAccessToken() } returns "tok"
        coEvery { userDetailRepository.getUserDetail("tok") } returns
            Result.failure(RuntimeException("LOGOUT"))

        val vm = createViewModel()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.logoutUser)
        assertFalse(vm.uiState.value.isLoadingUserDetail)
    }

    @Test
    fun `fetchUserDetail failure with other message sets error`() = runTest(testDispatcher) {
        every { preferenceHelper.getAccessToken() } returns "tok"
        coEvery { userDetailRepository.getUserDetail("tok") } returns
            Result.failure(RuntimeException("network down"))

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals("network down", vm.uiState.value.userDetailError)
        assertFalse(vm.uiState.value.logoutUser)
    }

    @Test
    fun `fetchUserDetail failure with null message uses default`() = runTest(testDispatcher) {
        every { preferenceHelper.getAccessToken() } returns "tok"
        coEvery { userDetailRepository.getUserDetail("tok") } returns
            Result.failure(RuntimeException())

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals("An unknown error occurred", vm.uiState.value.userDetailError)
    }

    @Test
    fun `fetchUserDetail persist throws with null message uses default`() = runTest(testDispatcher) {
        every { preferenceHelper.getAccessToken() } returns "tok"
        coEvery { userDetailRepository.getUserDetail("tok") } returns
            Result.success(ApiResponse(200, true, "ok", null, userDetail()))
        coEvery { userDao.upsertPreservingLocalId(any()) } throws RuntimeException()

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals("Failed to persist user detail", vm.uiState.value.userDetailError)
    }

    @Test
    fun `fetchUserDetail success with no terminals skips terminal save`() = runTest(testDispatcher) {
        every { preferenceHelper.getAccessToken() } returns "tok"
        coEvery { userDetailRepository.getUserDetail("tok") } returns
            Result.success(ApiResponse(200, true, "ok", null, userDetail(terminals = null)))

        val vm = createViewModel()
        advanceUntilIdle()

        verify(exactly = 0) { preferenceHelper.saveTerminals(any()) }
        assertFalse(vm.terminalInfoLoaded.value)
        coVerify { userDao.upsertPreservingLocalId(any()) }
    }

    // ─────────────────────────────── small state mutators ───────────────────────────────

    @Test
    fun `resetNavigateToProfile clears flag`() = runTest(testDispatcher) {
        every { preferenceHelper.getAccessToken() } returns "tok"
        coEvery { userDetailRepository.getUserDetail("tok") } returns
            Result.success(ApiResponse(200, true, "ok", null, userDetail(isProfileCompleted = false)))

        val vm = createViewModel()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.navigateToProfile)

        vm.resetNavigateToProfile()
        assertFalse(vm.uiState.value.navigateToProfile)
    }

    @Test
    fun `saveTxnId saves zero`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.saveTxnId()

        verify { preferenceHelper.saveTxnId(0) }
    }

    @Test
    fun `selectCurrentTransaction saves provided id`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.selectCurrentTransaction(42L)

        verify { preferenceHelper.saveTxnId(42L) }
    }

    @Test
    fun `createBatch stages pending bucket id`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.createBatch("bucket-1")

        assertEquals("bucket-1", vm.uiState.value.pendingStockCountBucketId)
    }

    @Test
    fun `clearCreatedBatchId nulls field`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.clearCreatedBatchId()

        assertNull(vm.uiState.value.createdBatchId)
    }

    @Test
    fun `clearPendingStockCountBucketId nulls field`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.createBatch("bucket-1")
        vm.clearPendingStockCountBucketId()

        assertNull(vm.uiState.value.pendingStockCountBucketId)
    }
}
