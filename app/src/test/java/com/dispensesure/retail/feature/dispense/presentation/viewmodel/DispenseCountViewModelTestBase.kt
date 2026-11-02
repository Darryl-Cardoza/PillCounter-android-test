package com.dispensesure.retail.feature.dispense.presentation.viewmodel

import android.app.Application
import com.dispensesure.retail.core.room.dao.UserDao
import com.dispensesure.retail.core.room.models.dtos.DispenseTxnDetail
import com.dispensesure.retail.core.scanning.domain.model.DecodedScan
import com.dispensesure.retail.core.scanning.engine.PhotoLabel
import com.dispensesure.retail.core.scanning.engine.TestEngineFactory
import com.dispensesure.retail.core.scanning.logic.PillDetectionModelLoader
import com.dispensesure.retail.core.scanning.logic.ScanDecoder
import com.dispensesure.retail.core.utils.common.LocationProvider
import com.dispensesure.retail.core.utils.logger.PerformanceLogger
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.feature.dispense.data.DispenseCountRepository
import com.dispensesure.retail.feature.dispense.domain.model.DispenseCountAction
import com.dispensesure.retail.feature.dispense.domain.model.DispenseCountEffect
import com.dispensesure.retail.feature.dispense.domain.model.DispenseCountUiState
import com.dispensesure.retail.feature.dispense.domain.model.DispenseStage
import com.dispensesure.retail.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.After
import org.junit.Before
import org.junit.Rule

/**
 * Shared setup for the [DispenseCountViewModel] tests: the repository and the scan decoder
 * are mocks, the engine is real (built from mocks), plus helpers to load a txn, set state
 * and collect effects.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class DispenseCountViewModelTestBase {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    protected val application: Application = mockk(relaxed = true)
    protected val preferenceHelper: PreferenceHelper = mockk(relaxed = true)
    protected val dispenseCountRepository: DispenseCountRepository = mockk(relaxed = true)
    protected val scanDecoder: ScanDecoder = mockk()
    protected val userDao: UserDao = mockk(relaxed = true)
    protected val locationProvider: LocationProvider = mockk(relaxed = true)
    protected val modelLoader: PillDetectionModelLoader = mockk(relaxed = true)
    protected val performanceLogger: PerformanceLogger = mockk(relaxed = true)

    protected lateinit var engineFactory: TestEngineFactory
    protected lateinit var viewModel: DispenseCountViewModel

    @Before
    fun setUpViewModel() {
        every { application.applicationContext } returns application
        every { application.getString(any<Int>()) } returns "test error"
        every { application.getString(any<Int>(), any()) } returns "test error"
        coJustRun { performanceLogger.logPerformanceSnapshot(any()) }
        every { preferenceHelper.getControlDrugTypes() } returns emptySet()
        every { preferenceHelper.isRequireDoubleCountEnabled() } returns false
        every { preferenceHelper.isRequireBackCountEnabled() } returns false
        every { scanDecoder.decode(any()) } returns DecodedScan(isGs1 = false, gtin14 = null, lot = null, expiry = null, serial = null)
        // Nullable and enum results are stubbed: a relaxed mock would hand back a child mock.
        every { dispenseCountRepository.observeCounts(any(), any()) } returns flowOf(emptyList())
        every { dispenseCountRepository.observeCanReset(any()) } returns flowOf(true)
        coEvery { dispenseCountRepository.loadTxn(any()) } returns null
        coEvery { dispenseCountRepository.loadDrug(any()) } returns null
        coEvery { dispenseCountRepository.latestCountStep(any()) } returns null
        coEvery { dispenseCountRepository.findLocalDrug(any()) } returns null
        coEvery { dispenseCountRepository.loadBottles(any()) } returns null
        coEvery { dispenseCountRepository.loadVialPhoto(any()) } returns null
        coEvery { dispenseCountRepository.complete(any()) } returns null
        coEvery { dispenseCountRepository.countPhotoLabel(any(), any()) } returns photoLabel()

        engineFactory = TestEngineFactory(
            context = application,
            modelLoader = modelLoader,
            performanceLogger = performanceLogger,
            locationProvider = locationProvider,
            userDao = userDao,
            preferenceHelper = preferenceHelper,
        )
        viewModel = DispenseCountViewModel(
            preferenceHelper = preferenceHelper,
            scanDecoder = scanDecoder,
            dispenseCountRepository = dispenseCountRepository,
            engineFactory = engineFactory,
        )
    }

    @After
    fun tearDownMocks() {
        unmockkAll()
    }

    /** A dispense txn as the repository loads it; tests set what they need. */
    protected fun dispenseTxn(
        txnId: Long = 42L,
        ndc: String? = null,
        targetCount: Int? = 30,
        isComingFromHL7: Boolean = false,
        workflowStep: String? = null,
        bucketId: String? = null,
    ) = DispenseTxnDetail(
        txnId = txnId,
        drugName = "Drug",
        drugId = 1L,
        ndc = ndc,
        targetCount = targetCount,
        note = null,
        createdAt = 0L,
        bottlesJson = null,
        totalPillCount = 0,
        drugType = null,
        bucketId = bucketId,
        counts = emptyList(),
        isComingFromHL7 = isComingFromHL7,
        workflowStep = workflowStep,
    )

    protected fun photoLabel(
        lotNumber: String? = null,
        serialNumber: String? = null,
        expirationDate: String? = null,
        rxRefill: String? = null,
    ) = PhotoLabel(
        drugName = "Drug",
        drugImagePath = null,
        isStockCount = false,
        requestedNdc = "NDC1",
        dispensedNdc = "NDC1",
        lotNumber = lotNumber,
        serialNumber = serialNumber,
        expirationDate = expirationDate,
        rxRefill = rxRefill,
        stepLabel = "TARGET_VERIFICATION",
    )

    /** Starts counting on [txn], as the screen does when the stage becomes COUNTING. */
    protected fun TestScope.startCounting(txn: DispenseTxnDetail = dispenseTxn()) {
        coEvery { dispenseCountRepository.loadTxn(txn.txnId) } returns txn
        viewModel.onAction(DispenseCountAction.StageChanged(DispenseStage.COUNTING, txnId = txn.txnId, isHazardousDrug = false))
        advanceUntilIdle()
    }

    /** Hands the view model [txnId] the way a stage change does, without starting the count. */
    protected fun selectTxn(txnId: Long) {
        viewModel.onAction(DispenseCountAction.StageChanged(DispenseStage.PRE_RX, txnId = txnId, isHazardousDrug = false))
    }

    /** Sets the view model's state directly; steps and the current step have no other way in. */
    protected fun setState(change: (DispenseCountUiState) -> DispenseCountUiState) {
        val field = DispenseCountViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val uiStateFlow = field.get(viewModel) as MutableStateFlow<DispenseCountUiState>
        uiStateFlow.value = change(uiStateFlow.value)
    }

    /** Collects every effect the view model sends from here on. */
    protected fun TestScope.collectEffects(): List<DispenseCountEffect> {
        val effects = mutableListOf<DispenseCountEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.effects.collect { effect -> effects += effect }
        }
        return effects
    }
}
