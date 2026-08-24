package com.rite.pillcounting.feature.history.viewmodel

import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.models.dtos.TxnDetailInfo
import com.rite.pillcounting.core.room.models.dtos.TxnWithDetails
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.history.presentation.viewmodel.HistoryDetailsViewModel
import com.rite.pillcounting.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [HistoryDetailsViewModel].
 *
 * getTransactionDetails() is called in init via viewModelScope.launch (no Dispatchers.IO
 * override), so advanceUntilIdle() is sufficient — no Turbine needed for these tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryDetailsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val preferenceHelper: PreferenceHelper = mockk(relaxed = true)
    private val pillCountTxnDao: PillCountTxnDao = mockk(relaxed = true)

    @Before
    fun setup() {
        every { preferenceHelper.getTxnId() } returns 1L
    }

    @After
    fun tearDown() { unmockkAll() }

    private fun createViewModel() = HistoryDetailsViewModel(preferenceHelper, pillCountTxnDao)

    private fun buildTxnWithDetails(txnDetails: List<TxnDetailInfo> = emptyList()) =
        TxnWithDetails(
            txnId = 1L,
            drugName = "Aspirin",
            drugId = 100L,
            ndc = "12345",
            targetCount = 10,
            note = null,
            createdAt = 1_000_000L,
            bottleInfoListJson = null,
            totalPillCount = 10,
            isDispense = true,
            drugType = null,
            txnDetails = txnDetails,
            isComingFromHL7 = false
        )

    // HIST_DET_VM_001
    @Test
    fun `init sets txnInfo to null when DAO returns null`() = runTest {
        coEvery { pillCountTxnDao.getTxnWithDetails(any()) } returns null

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.txnInfo)
    }

    // HIST_DET_VM_002
    @Test
    fun `init sets txnInfo when DAO returns a transaction`() = runTest {
        coEvery { pillCountTxnDao.getTxnWithDetails(1L) } returns buildTxnWithDetails()

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.txnInfo)
        assertEquals(1L, viewModel.uiState.value.txnInfo?.txnId)
    }

    // HIST_DET_VM_003
    @Test
    fun `init filters out soft-deleted txnDetails`() = runTest {
        val activeDetail = TxnDetailInfo(txnId = 1L, pillCount = 5, imagePath = null, type = null, isDeleted = false)
        val deletedDetail = TxnDetailInfo(txnId = 1L, pillCount = 3, imagePath = null, type = null, isDeleted = true)
        coEvery { pillCountTxnDao.getTxnWithDetails(any()) } returns buildTxnWithDetails(listOf(activeDetail, deletedDetail))

        val viewModel = createViewModel()
        advanceUntilIdle()

        val details = viewModel.uiState.value.txnInfo?.txnDetails
        assertEquals(1, details?.size)
        assertEquals(false, details?.get(0)?.isDeleted)
    }

    // HIST_DET_VM_004
    @Test
    fun `init uses txnId from preferenceHelper`() = runTest {
        every { preferenceHelper.getTxnId() } returns 42L
        coEvery { pillCountTxnDao.getTxnWithDetails(42L) } returns buildTxnWithDetails()
        coEvery { pillCountTxnDao.getTxnWithDetails(neq(42L)) } returns null

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.txnInfo)
    }

    // HIST_DET_VM_005
    @Test
    fun `deleteTransaction calls pillCountTxnDao softDelete with current txnId`() = runTest {
        coEvery { pillCountTxnDao.getTxnWithDetails(any()) } returns null
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteTransaction()
        advanceUntilIdle()

        coVerify { pillCountTxnDao.softDelete(1L, any()) }
    }

    // HIST_DET_VM_006
    @Test
    fun `getCurrentUser returns the logged-in email`() = runTest {
        every { preferenceHelper.getLoggedInEmail() } returns "alice@pharmacy.com"
        coEvery { pillCountTxnDao.getTxnWithDetails(any()) } returns null
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals("alice@pharmacy.com", viewModel.getCurrentUser())
    }

    // HIST_DET_VM_007
    @Test
    fun `getCurrentUser falls back to getUserId when no logged-in email`() = runTest {
        every { preferenceHelper.getLoggedInEmail() } returns null
        every { preferenceHelper.getUserId() } returns "user-99"
        coEvery { pillCountTxnDao.getTxnWithDetails(any()) } returns null
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals("user-99", viewModel.getCurrentUser())
    }

    // HIST_DET_VM_008
    @Test
    fun `getCurrentUser returns em-dash when both logged-in email and userId are absent`() = runTest {
        every { preferenceHelper.getLoggedInEmail() } returns null
        every { preferenceHelper.getUserId() } returns null
        coEvery { pillCountTxnDao.getTxnWithDetails(any()) } returns null
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals("—", viewModel.getCurrentUser())
    }
}
