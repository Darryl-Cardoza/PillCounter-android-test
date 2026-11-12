package com.dispensesure.retail.feature.dispense.data

import com.dispensesure.retail.core.models.StepState.CONTAINER_INITIATE
import com.dispensesure.retail.core.models.StepState.CONTAINER_PENDING
import com.dispensesure.retail.core.models.StepState.SCAN
import com.dispensesure.retail.core.models.StepState.TARGET_REVERIFICATION
import com.dispensesure.retail.core.models.StepState.TARGET_VERIFICATION
import com.dispensesure.retail.core.models.StepState.VIAL
import com.dispensesure.retail.core.region.Region
import com.dispensesure.retail.core.region.RegionProvider
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Unit tests for [DispenseStepResolver]: settings and saved progress in, steps out. */
class DispenseStepResolverTest {

    private val txnId = 7L
    private val preferenceHelper: PreferenceHelper = mockk()
    private val regionProvider: RegionProvider = mockk { every { current } returns Region.US }
    private val dispenseCountRepository: DispenseCountRepository = mockk()
    private val resolver = DispenseStepResolver(preferenceHelper, regionProvider, dispenseCountRepository)

    @Before
    fun setUp() {
        every { preferenceHelper.isRequireDoubleCountEnabled() } returns false
        every { preferenceHelper.isRequireBackCountEnabled() } returns false
        every { preferenceHelper.getControlDrugTypes(Region.US) } returns setOf("CII")
        coEvery { dispenseCountRepository.latestCountStep(txnId) } returns null
    }

    private suspend fun resumeStep(
        isContainerScanned: Boolean = true,
        savedWorkflowStep: String? = null,
        drugType: String? = "REGULAR",
    ) = resolver.resumeStep(txnId, isContainerScanned, savedWorkflowStep, drugType)

    @Test
    fun `an order whose container isn't scanned resumes on SCAN`() = runTest {
        assertEquals(SCAN, resumeStep(isContainerScanned = false, savedWorkflowStep = VIAL.name))
    }

    @Test
    fun `nothing saved and no counts resumes on the pour-out`() = runTest {
        assertEquals(CONTAINER_INITIATE, resumeStep())
    }

    @Test
    fun `nothing saved for a drug with no type resumes on the target count`() = runTest {
        assertEquals(TARGET_VERIFICATION, resumeStep(drugType = null))
    }

    @Test
    fun `nothing saved resumes on the latest counted step`() = runTest {
        coEvery { dispenseCountRepository.latestCountStep(txnId) } returns TARGET_VERIFICATION

        assertEquals(TARGET_VERIFICATION, resumeStep())
    }

    @Test
    fun `a saved step resumes on that step`() = runTest {
        assertEquals(VIAL, resumeStep(savedWorkflowStep = VIAL.name))
    }

    @Test
    fun `a saved recount removed by settings resumes on the next step`() = runTest {
        assertEquals(VIAL, resumeStep(savedWorkflowStep = TARGET_REVERIFICATION.name, drugType = "CII"))
    }

    @Test
    fun `a saved back count removed by settings resumes on the last step`() = runTest {
        assertEquals(VIAL, resumeStep(savedWorkflowStep = CONTAINER_PENDING.name))
    }

    @Test
    fun `a saved value that isn't a step is ignored`() = runTest {
        assertEquals(CONTAINER_INITIATE, resumeStep(savedWorkflowStep = "JUNK"))
    }

    @Test
    fun `steps follow the double count and back count settings`() {
        every { preferenceHelper.isRequireDoubleCountEnabled() } returns true
        every { preferenceHelper.isRequireBackCountEnabled() } returns true

        assertEquals(
            listOf(SCAN, CONTAINER_INITIATE, TARGET_VERIFICATION, TARGET_REVERIFICATION, VIAL, CONTAINER_PENDING),
            resolver.steps(isTxnLoaded = true, drugType = "CII"),
        )
    }
}
