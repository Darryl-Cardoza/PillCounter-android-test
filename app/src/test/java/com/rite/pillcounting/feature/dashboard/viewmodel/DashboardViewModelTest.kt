package com.rite.pillcounting.feature.dashboard.viewmodel

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.rite.pillcounting.core.models.ApiResponse
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.UserDao
import com.rite.pillcounting.core.utils.device.DeviceKeyProvider
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.dashboard.domain.data.IUserDetailRepository
import com.rite.pillcounting.feature.dashboard.domain.model.DashboardTab
import com.rite.pillcounting.feature.dashboard.domain.model.KpiFilter
import com.rite.pillcounting.feature.dashboard.domain.model.Terminal
import com.rite.pillcounting.feature.dashboard.domain.model.UserDetail
import com.rite.pillcounting.feature.dashboard.domain.model.UserProfile
import com.rite.pillcounting.feature.dashboard.domain.model.UserSettings
import com.rite.pillcounting.feature.dashboard.presentation.viewmodel.DashboardViewModel
import com.rite.pillcounting.feature.hl7.core.Hl7EventHandler
import com.rite.pillcounting.feature.hl7.core.Hl7ServiceManager
import com.rite.pillcounting.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
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
 * Unit tests for [DashboardViewModel].
 *
 * fetchUserDetail() is launched on Dispatchers.IO in init, so advanceUntilIdle() cannot wait
 * for it — it only advances the test scheduler, not real IO threads. Tests that check state set
 * by fetchUserDetail use Turbine's awaitItem(), which genuinely blocks until the emission arrives
 * regardless of which thread emits it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository: IUserDetailRepository = mockk()
    private val preferenceHelper: PreferenceHelper = mockk(relaxed = true)
    private val userDao: UserDao = mockk(relaxed = true)
    private val batchDao: BatchDao = mockk(relaxed = true)
    private val pillCountTxnDao: PillCountTxnDao = mockk(relaxed = true)
    private val hl7EventHandler: Hl7EventHandler = mockk(relaxed = true)
    private val hl7ServiceManager: Hl7ServiceManager = mockk(relaxed = true)
    private val deviceKeyProvider: DeviceKeyProvider = mockk(relaxed = true)

    @Before
    fun setup() {
        every { hl7EventHandler.connectionState } returns MutableStateFlow(false)
        every { hl7EventHandler.pmsCertMismatch } returns MutableStateFlow(false)
        // localId=0 → observeQueue/observeUserDetail no-op; getAccessToken=null → fetchUserDetail exits early
        every { preferenceHelper.getLocalId() } returns 0L
        every { preferenceHelper.getAccessToken() } returns null
    }

    @After
    fun tearDown() { unmockkAll() }

    private fun createViewModel() = DashboardViewModel(
        userDetailRepository = repository,
        preferenceHelper = preferenceHelper,
        userDao = userDao,
        batchDao = batchDao,
        pillCountTxnDao = pillCountTxnDao,
        hl7EventHandler = hl7EventHandler,
        hl7ServiceManager = hl7ServiceManager,
        deviceKeyProvider = deviceKeyProvider,
    )

    // Consumes turbine items until the predicate is satisfied, then returns the matching item.
    private suspend fun <T> ReceiveTurbine<T>.skipUntil(predicate: (T) -> Boolean): T {
        var item = awaitItem()
        while (!predicate(item)) {
            item = awaitItem()
        }
        return item
    }

    // ─────────────────────────── fetchUserDetail (triggered from init on IO thread) ───────────────────────────

    // DASH_VM_001
    @Test
    fun `fetchUserDetail sets userDetailError when access token is null`() = runTest {
        val viewModel = createViewModel()

        viewModel.uiState.test {
            val state = skipUntil { it.userDetailError != null }
            assertEquals("Access token not found", state.userDetailError)
            assertFalse(state.isLoadingUserDetail)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // DASH_VM_002
    @Test
    fun `fetchUserDetail persists user saves prefs and sets terminalInfoLoaded on API success`() = runTest {
        val token = "access-tok"
        // The terminal must carry this install's device_key to be adopted. is_active alone marks
        // it enabled for the account and is shared by every device signed into that account, so
        // selecting on it handed all of them the same terminal.
        val terminal = Terminal(
            terminalId = "T1",
            terminalName = "Main Terminal",
            isActive = true,
            deviceKey = "device-A"
        )
        val profile = UserProfile(userId = "u1", isProfileCompleted = true)
        val userDetail = UserDetail(profile = profile, settings = UserSettings(terminals = listOf(terminal)))
        val apiResponse = ApiResponse(status = 200, isSuccess = true, message = "OK", token = null, data = userDetail)

        every { preferenceHelper.getAccessToken() } returns token
        coEvery { deviceKeyProvider.getDeviceKey() } returns "device-A"
        coEvery { repository.getUserDetail(token) } returns Result.success(apiResponse)
        coEvery { userDao.upsertPreservingLocalId(any()) } returns 42L
        every { userDao.observeByLocalId(any()) } returns flowOf(null)
        every { pillCountTxnDao.observePartialByIsDispense(any(), any(), any(), any()) } returns flowOf(emptyList())
        every { batchDao.observeInProgressBatchSummaries() } returns flowOf(emptyList())

        val viewModel = createViewModel()

        // observeQueue (triggered by success path when getLocalId()==0) sets kpiCounts to a
        // 6-entry map even for an empty queue — the initial emptyMap() becomes non-empty.
        // Waiting for both conditions ensures all IO work (including saveLocalId) is complete.
        viewModel.uiState.test {
            val state = skipUntil { !it.isLoadingUserDetail && it.kpiCounts.isNotEmpty() }
            assertNull(state.userDetailError)
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(viewModel.terminalInfoLoaded.value)
        verify { preferenceHelper.saveUserId(any()) }
        verify { preferenceHelper.saveLocalId(42L) }
        verify { preferenceHelper.saveTerminals(listOf(terminal)) }
        verify { preferenceHelper.saveSelectedTerminalId("T1") }
        verify { preferenceHelper.saveSelectedTerminalName("Main Terminal") }
    }

    // DASH_VM_003
    @Test
    fun `fetchUserDetail sets navigateToProfile when profile is incomplete`() = runTest {
        val token = "access-tok"
        val profile = UserProfile(userId = "u1", isProfileCompleted = false)
        val userDetail = UserDetail(profile = profile, settings = null)
        val apiResponse = ApiResponse(status = 200, isSuccess = true, message = "OK", token = null, data = userDetail)

        every { preferenceHelper.getAccessToken() } returns token
        coEvery { repository.getUserDetail(token) } returns Result.success(apiResponse)
        coEvery { userDao.upsertPreservingLocalId(any()) } returns 10L
        every { userDao.observeByLocalId(any()) } returns flowOf(null)
        every { pillCountTxnDao.observePartialByIsDispense(any(), any(), any(), any()) } returns flowOf(emptyList())
        every { batchDao.observeInProgressBatchSummaries() } returns flowOf(emptyList())

        val viewModel = createViewModel()

        viewModel.uiState.test {
            val state = skipUntil { it.navigateToProfile }
            assertTrue(state.navigateToProfile)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // DASH_VM_004
    @Test
    fun `fetchUserDetail sets logoutUser when repository fails with LOGOUT`() = runTest {
        val token = "access-tok"
        every { preferenceHelper.getAccessToken() } returns token
        coEvery { repository.getUserDetail(token) } returns Result.failure(Exception("LOGOUT"))

        val viewModel = createViewModel()

        viewModel.uiState.test {
            val state = skipUntil { it.logoutUser }
            assertTrue(state.logoutUser)
            assertFalse(state.isLoadingUserDetail)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // DASH_VM_005
    @Test
    fun `fetchUserDetail sets userDetailError on non-LOGOUT repository failure`() = runTest {
        val token = "access-tok"
        every { preferenceHelper.getAccessToken() } returns token
        coEvery { repository.getUserDetail(token) } returns Result.failure(Exception("Network timeout"))

        val viewModel = createViewModel()

        viewModel.uiState.test {
            val state = skipUntil { !it.isLoadingUserDetail && it.userDetailError != null }
            assertEquals("Network timeout", state.userDetailError)
            assertFalse(state.logoutUser)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─────────────────────────── onKpiFilterTapped ───────────────────────────

    // DASH_VM_006
    @Test
    fun `onKpiFilterTapped sets activeKpiFilter when no filter is active`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.activeKpiFilter)

        viewModel.onKpiFilterTapped(KpiFilter.DISP_PENDING)

        assertEquals(KpiFilter.DISP_PENDING, viewModel.uiState.value.activeKpiFilter)
    }

    // DASH_VM_007
    @Test
    fun `onKpiFilterTapped clears activeKpiFilter when same filter is tapped twice`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.onKpiFilterTapped(KpiFilter.DISP_HAZARDOUS)
        assertEquals(KpiFilter.DISP_HAZARDOUS, viewModel.uiState.value.activeKpiFilter)

        viewModel.onKpiFilterTapped(KpiFilter.DISP_HAZARDOUS)

        assertNull(viewModel.uiState.value.activeKpiFilter)
    }

    // DASH_VM_008
    @Test
    fun `onKpiFilterTapped switches activeTab back to TODAYS_QUEUE`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.onTabSelected(DashboardTab.RECENT_ACTIVITY)

        viewModel.onKpiFilterTapped(KpiFilter.DISP_PENDING)

        assertEquals(DashboardTab.TODAYS_QUEUE, viewModel.uiState.value.activeTab)
    }

    // ─────────────────────────── onTabSelected ───────────────────────────

    // DASH_VM_009
    @Test
    fun `onTabSelected RECENT_ACTIVITY clears activeKpiFilter and updates tab`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.onKpiFilterTapped(KpiFilter.DISP_PENDING)

        viewModel.onTabSelected(DashboardTab.RECENT_ACTIVITY)

        val state = viewModel.uiState.value
        assertEquals(DashboardTab.RECENT_ACTIVITY, state.activeTab)
        assertNull(state.activeKpiFilter)
    }

    // DASH_VM_010
    @Test
    fun `onTabSelected TODAYS_QUEUE preserves activeKpiFilter`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.onKpiFilterTapped(KpiFilter.DISP_CONTROLLED)

        viewModel.onTabSelected(DashboardTab.TODAYS_QUEUE)

        val state = viewModel.uiState.value
        assertEquals(DashboardTab.TODAYS_QUEUE, state.activeTab)
        assertEquals(KpiFilter.DISP_CONTROLLED, state.activeKpiFilter)
    }

    // ─────────────────────────── createBatch / selectCurrentTransaction / resetNavigateToProfile ───────────────────────────

    // DASH_VM_011
    @Test
    fun `createBatch sets pendingStockCountBucketId in uiState`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.pendingStockCountBucketId)

        viewModel.createBatch("BUCKET_42")

        assertEquals("BUCKET_42", viewModel.uiState.value.pendingStockCountBucketId)
    }

    // DASH_VM_012
    @Test
    fun `selectCurrentTransaction delegates txnId to preferenceHelper`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.selectCurrentTransaction(99L)

        verify { preferenceHelper.saveTxnId(99L) }
    }

    // DASH_VM_013
    @Test
    fun `resetNavigateToProfile clears navigateToProfile flag after profile-incomplete fetch`() = runTest {
        val token = "access-tok"
        val profile = UserProfile(userId = "u1", isProfileCompleted = false)
        val userDetail = UserDetail(profile = profile, settings = null)
        val apiResponse = ApiResponse(status = 200, isSuccess = true, message = "OK", token = null, data = userDetail)

        every { preferenceHelper.getAccessToken() } returns token
        coEvery { repository.getUserDetail(token) } returns Result.success(apiResponse)
        coEvery { userDao.upsertPreservingLocalId(any()) } returns 10L
        every { userDao.observeByLocalId(any()) } returns flowOf(null)
        every { pillCountTxnDao.observePartialByIsDispense(any(), any(), any(), any()) } returns flowOf(emptyList())
        every { batchDao.observeInProgressBatchSummaries() } returns flowOf(emptyList())

        val viewModel = createViewModel()

        viewModel.uiState.test {
            skipUntil { it.navigateToProfile }
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.resetNavigateToProfile()

        assertFalse(viewModel.uiState.value.navigateToProfile)
    }
}
