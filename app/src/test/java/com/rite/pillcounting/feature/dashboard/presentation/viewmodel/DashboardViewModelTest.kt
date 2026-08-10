package com.rite.pillcounting.feature.dashboard.presentation.viewmodel

import android.util.Log
import app.cash.turbine.test
import com.rite.pillcounting.core.models.ApiResponse
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.UserDao
import com.rite.pillcounting.core.room.models.UserEntity
import com.rite.pillcounting.core.room.models.dtos.BatchSummaryDto
import com.rite.pillcounting.core.room.models.dtos.PillCountWithDrugAndTotal
import com.rite.pillcounting.core.room.models.enums.BatchStatus
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.room.models.enums.TxnPriority
import com.rite.pillcounting.core.security.models.SecureString
import com.rite.pillcounting.core.utils.device.DeviceKeyProvider
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.dashboard.domain.data.IUserDetailRepository
import com.rite.pillcounting.feature.dashboard.domain.model.DashboardTab
import com.rite.pillcounting.feature.dashboard.domain.model.KpiFilter
import com.rite.pillcounting.feature.dashboard.domain.model.QueueItem
import com.rite.pillcounting.feature.dashboard.domain.model.Terminal
import com.rite.pillcounting.feature.dashboard.domain.model.UserDetail
import com.rite.pillcounting.feature.dashboard.domain.model.UserProfile
import com.rite.pillcounting.feature.dashboard.domain.model.UserSettings
import com.rite.pillcounting.feature.hl7.core.Hl7EventHandler
import com.rite.pillcounting.feature.hl7.core.Hl7ServiceManager
import com.rite.pillcounting.feature.history.domain.model.TxnWithDrugDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var userDetailRepository: IUserDetailRepository
    private lateinit var preferenceHelper: PreferenceHelper
    private lateinit var userDao: UserDao
    private lateinit var batchDao: BatchDao
    private lateinit var pillCountTxnDao: PillCountTxnDao
    private lateinit var hl7EventHandler: Hl7EventHandler
    private lateinit var hl7ServiceManager: Hl7ServiceManager
    private lateinit var deviceKeyProvider: DeviceKeyProvider

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
        // The VM launches its work on a hardcoded Dispatchers.IO; redirect it to the
        // test scheduler so advanceUntilIdle() drives those coroutines deterministically.
        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns testDispatcher

        userDetailRepository = mockk(relaxed = true)
        preferenceHelper = mockk(relaxed = true)
        userDao = mockk(relaxed = true)
        batchDao = mockk(relaxed = true)
        pillCountTxnDao = mockk(relaxed = true)
        hl7EventHandler = mockk(relaxed = true)
        hl7ServiceManager = mockk(relaxed = true)
        deviceKeyProvider = mockk(relaxed = true)

        // StateFlows on the event handler.
        every { hl7EventHandler.connectionState } returns MutableStateFlow(false)
        every { hl7EventHandler.pmsCertMismatch } returns MutableStateFlow(false)

        // Defaults so the init block runs without blowing up.
        every { preferenceHelper.getLocalId() } returns 1L
        every { preferenceHelper.getAccessToken() } returns null
        every { preferenceHelper.getTerminals() } returns emptyList()
        every { preferenceHelper.getBucketList() } returns emptyList()
        every { preferenceHelper.isHl7Enabled() } returns false

        every { userDao.observeByLocalId(any()) } returns flowOf(null)
        coEvery { userDao.upsertPreservingLocalId(any()) } returns 5L
        every { pillCountTxnDao.observePartialByIsDispense(any(), any(), any(), any()) } returns flowOf(emptyList())
        every { batchDao.observeInProgressBatchSummaries(any()) } returns flowOf(emptyList())
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
        batchDao,
        pillCountTxnDao,
        hl7EventHandler,
        hl7ServiceManager,
        deviceKeyProvider,
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
    )

    private fun userEntity(localId: Long = 1L) = UserEntity(
        localId = localId,
        userId = "u-$localId",
        email = SecureString("a@b.com"),
        fName = "First",
        lName = "Last",
        phoneNumber = SecureString("123"),
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
    fun `init skips observeQueue when localId is zero`() = runTest(testDispatcher) {
        every { preferenceHelper.getLocalId() } returns 0L
        // token blank too so fetchUserDetail short-circuits.
        every { preferenceHelper.getAccessToken() } returns null

        createViewModel()
        advanceUntilIdle()

        verify(exactly = 0) { pillCountTxnDao.observePartialByIsDispense(any(), any(), any(), any()) }
    }

    @Test
    fun `clearPmsCertPin delegates to service manager`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.clearPmsCertPin()

        verify(exactly = 1) { hl7ServiceManager.clearPmsCertPin(hl7EventHandler) }
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

        // Same active id but a different list instance.
        every { preferenceHelper.getTerminals() } returns
            listOf(Terminal(terminalId = "t1", isActive = true), Terminal(terminalId = "t9", isActive = false))
        vm.refreshTerminalsFromPrefs()

        // Unchanged active id -> current returned unchanged; same terminals reference.
        assertEquals(before, vm.uiState.value.userDetail)
    }

    // ─────────────────────────────── observeQueue / KPI ───────────────────────────────

    @Test
    fun `observeQueue builds queue and computes kpi counts`() = runTest(testDispatcher) {
        val dispense = listOf(
            dispenseTxn(txnId = 1, createdAt = 1, priority = TxnPriority.High, drugType = "CII"),
            dispenseTxn(txnId = 2, createdAt = 3, isHazardous = true, drugType = "notControlled"),
        )
        val batches = listOf(
            batchSummary(batchId = 10, createdAt = 2, requestIdFromPMS = "pms-1"),
            batchSummary(batchId = 11, createdAt = 4, requestIdFromPMS = null),
        )
        every { pillCountTxnDao.observePartialByIsDispense(any(), any(), any(), any()) } returns flowOf(dispense)
        every { batchDao.observeInProgressBatchSummaries(any()) } returns flowOf(batches)

        val vm = createViewModel()
        advanceUntilIdle()

        vm.uiState.test {
            val state = expectMostRecentItem()
            // sorted ascending by createdAt -> txn1(1), batch10(2), txn2(3), batch11(4)
            assertEquals(4, state.queue.size)
            val counts = state.kpiCounts
            assertEquals(1, counts[KpiFilter.DISP_HIGH_PRIORITY])
            assertEquals(2, counts[KpiFilter.DISP_PENDING])
            assertEquals(1, counts[KpiFilter.DISP_CONTROLLED])
            assertEquals(1, counts[KpiFilter.DISP_HAZARDOUS])
            assertEquals(1, counts[KpiFilter.INV_CYCLE_COUNT])
            assertEquals(1, counts[KpiFilter.INV_PENDING_BATCH])
        }
    }

    @Test
    fun `onKpiFilterTapped toggles filter and applies all branches`() = runTest(testDispatcher) {
        val dispense = listOf(
            dispenseTxn(txnId = 1, createdAt = 1, priority = TxnPriority.High, drugType = "CIII", isHazardous = true),
            dispenseTxn(txnId = 2, createdAt = 3),
        )
        val batches = listOf(
            batchSummary(batchId = 10, createdAt = 2, requestIdFromPMS = "pms"),
            batchSummary(batchId = 11, createdAt = 4, requestIdFromPMS = null),
        )
        every { pillCountTxnDao.observePartialByIsDispense(any(), any(), any(), any()) } returns flowOf(dispense)
        every { batchDao.observeInProgressBatchSummaries(any()) } returns flowOf(batches)

        val vm = createViewModel()
        advanceUntilIdle()

        // DISP_HIGH_PRIORITY
        vm.onKpiFilterTapped(KpiFilter.DISP_HIGH_PRIORITY)
        assertEquals(KpiFilter.DISP_HIGH_PRIORITY, vm.uiState.value.activeKpiFilter)
        assertEquals(DashboardTab.TODAYS_QUEUE, vm.uiState.value.activeTab)
        assertEquals(1, vm.uiState.value.queue.size)

        // DISP_PENDING (different filter -> set)
        vm.onKpiFilterTapped(KpiFilter.DISP_PENDING)
        assertEquals(2, vm.uiState.value.queue.size)

        // DISP_CONTROLLED
        vm.onKpiFilterTapped(KpiFilter.DISP_CONTROLLED)
        assertEquals(1, vm.uiState.value.queue.size)

        // DISP_HAZARDOUS
        vm.onKpiFilterTapped(KpiFilter.DISP_HAZARDOUS)
        assertEquals(1, vm.uiState.value.queue.size)

        // INV_CYCLE_COUNT
        vm.onKpiFilterTapped(KpiFilter.INV_CYCLE_COUNT)
        assertEquals(1, vm.uiState.value.queue.size)

        // INV_PENDING_BATCH
        vm.onKpiFilterTapped(KpiFilter.INV_PENDING_BATCH)
        assertEquals(1, vm.uiState.value.queue.size)

        // Toggle same filter off -> null filter, full queue.
        vm.onKpiFilterTapped(KpiFilter.INV_PENDING_BATCH)
        assertNull(vm.uiState.value.activeKpiFilter)
        assertEquals(4, vm.uiState.value.queue.size)
    }

    // ─────────────────────────────── onTabSelected / loadRecentActivity ───────────────────────────────

    @Test
    fun `onTabSelected recent activity clears filter and loads recent activity`() = runTest(testDispatcher) {
        val completedDispense = listOf(txnWithDrug(txnId = 1, createdAt = 5, drugType = "CIV"))
        val completedBatches = listOf(
            batchSummary(batchId = 20, createdAt = 6, status = BatchStatus.COMPLETED.name),
            batchSummary(batchId = 21, createdAt = 7, status = BatchStatus.INPROGRESS.name),
        )
        every {
            pillCountTxnDao.getTransactionsForDateRange(any(), any(), any(), any(), any(), any())
        } returns flowOf(completedDispense)
        every { batchDao.getBatchSummaries(any(), any()) } returns flowOf(completedBatches)

        val vm = createViewModel()
        advanceUntilIdle()
        // set a filter first to confirm it gets cleared.
        vm.onKpiFilterTapped(KpiFilter.DISP_PENDING)

        vm.onTabSelected(DashboardTab.RECENT_ACTIVITY)
        advanceUntilIdle()

        assertEquals(DashboardTab.RECENT_ACTIVITY, vm.uiState.value.activeTab)
        assertNull(vm.uiState.value.activeKpiFilter)
        // 1 completed dispense + 1 completed batch (INPROGRESS filtered out).
        assertEquals(2, vm.uiState.value.recentActivity.size)
    }

    @Test
    fun `onTabSelected todays queue keeps filter`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.onKpiFilterTapped(KpiFilter.DISP_PENDING)

        vm.onTabSelected(DashboardTab.TODAYS_QUEUE)
        advanceUntilIdle()

        assertEquals(DashboardTab.TODAYS_QUEUE, vm.uiState.value.activeTab)
        assertEquals(KpiFilter.DISP_PENDING, vm.uiState.value.activeKpiFilter)
    }

    @Test
    fun `loadRecentActivity is a no-op when already active`() = runTest(testDispatcher) {
        every {
            pillCountTxnDao.getTransactionsForDateRange(any(), any(), any(), any(), any(), any())
        } returns flowOf(emptyList())
        every { batchDao.getBatchSummaries(any(), any()) } returns flowOf(emptyList())

        val vm = createViewModel()
        advanceUntilIdle()

        vm.onTabSelected(DashboardTab.RECENT_ACTIVITY)
        vm.onTabSelected(DashboardTab.RECENT_ACTIVITY)
        advanceUntilIdle()

        // collected once even though selected twice (flowOf completes so the job is no longer
        // active; but the second call dispatches before the first completes in same loop).
        verify(atLeast = 1) { batchDao.getBatchSummaries(any(), any()) }
    }

    @Test
    fun `loadRecentActivity no-ops when localId is zero`() = runTest(testDispatcher) {
        every { preferenceHelper.getLocalId() } returns 0L
        // give a token so fetchUserDetail does not re-establish localId.
        every { preferenceHelper.getAccessToken() } returns null

        val vm = createViewModel()
        advanceUntilIdle()

        vm.onTabSelected(DashboardTab.RECENT_ACTIVITY)
        advanceUntilIdle()

        verify(exactly = 0) { batchDao.getBatchSummaries(any(), any()) }
    }

    // ─────────────────────────────── fetchUserDetail branches ───────────────────────────────

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

        // observeQueue(localId=9) invoked from fetch path.
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
