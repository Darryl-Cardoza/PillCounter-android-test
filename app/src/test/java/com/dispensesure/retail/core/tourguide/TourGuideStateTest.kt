package com.dispensesure.retail.core.tourguide

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

class TourGuideStateTest {

    private fun step(id: String, section: TourSection) = TourStep(id, 0, 0, section)

    private val steps = mapOf(
        TourSection.DASHBOARD to listOf(step("pms", TourSection.DASHBOARD), step("d1", TourSection.DASHBOARD)),
        TourSection.DISPENSE to listOf(step("x1", TourSection.DISPENSE), step("x2", TourSection.DISPENSE)),
        TourSection.STOCK_COUNT to listOf(step("s1", TourSection.STOCK_COUNT)),
    )

    private fun TestScopeState(scope: CoroutineScope) =
        TourGuideState(stepsFor = { steps.getValue(it) }, scope = scope)

    @Test
    fun `start concatenates the chosen sections and drops pms when HL7 is off`() = runTest {
        val state = TestScopeState(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.start(TourSection.entries, hl7Enabled = false)
        assertEquals(listOf("x1", "x2", "s1", "d1"), state.steps.map { it.id })
    }

    @Test
    fun `a prompt appears only between sections and Continue resumes at the next section`() = runTest {
        val state = TestScopeState(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.start(listOf(TourSection.DASHBOARD, TourSection.DISPENSE))

        state.advanceIfCurrent("pms")
        assertNull(state.pendingSection)
        state.advanceIfCurrent("d1")
        assertEquals(TourSection.DISPENSE, state.pendingSection)
        assertTrue(state.isActive)

        state.continueToNextSection()
        assertNull(state.pendingSection)
        assertEquals("x1", state.currentStep?.id)
    }

    @Test
    fun `the last step of a single section ends the tour without a prompt`() = runTest {
        val state = TestScopeState(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        var ended = 0
        state.onEnded = { ended++ }
        state.start(listOf(TourSection.STOCK_COUNT))

        state.advanceIfCurrent("s1")

        assertFalse(state.isActive)
        assertNull(state.pendingSection)
        assertEquals(1, ended)
    }

    @Test
    fun `advanceIfCurrent ignores a step that is not current`() = runTest {
        val state = TestScopeState(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.start(listOf(TourSection.DISPENSE))
        state.advanceIfCurrent("x2")
        assertEquals("x1", state.currentStep?.id)
    }

    @Test
    fun `a double tap runs the action and advances once`() = runTest {
        val state = TestScopeState(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.start(listOf(TourSection.DISPENSE))
        val gate = CompletableDeferred<Unit>()
        var runs = 0

        state.runStep("x1") { runs++; gate.await() }
        state.runStep("x1") { runs++; gate.await() }
        assertTrue(state.stepBusy)
        gate.complete(Unit)

        assertEquals(1, runs)
        assertEquals("x2", state.currentStep?.id)
        assertFalse(state.stepBusy)
    }

    @Test
    fun `a failing action stays on the step and can be retried`() = runTest {
        val state = TestScopeState(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.start(listOf(TourSection.DISPENSE))

        state.runStep("x1") { error("boom") }
        assertEquals("x1", state.currentStep?.id)
        assertEquals(1, state.failureTick)
        assertFalse(state.stepBusy)

        state.runStep("x1") {}
        assertEquals("x2", state.currentStep?.id)
    }

    @Test
    fun `start drops the note step when notes are off`() = runTest {
        val state = TourGuideState(
            stepsFor = { listOf(step("x1", it), step(NOTE_STEP_ID, it)) },
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        state.start(listOf(TourSection.DISPENSE), notesEnabled = false)
        assertEquals(listOf("x1"), state.steps.map { it.id })
        assertTrue(state.isLastStep)
    }

    @Test
    fun `canGoBack is false on the first step of a section`() = runTest {
        val state = TestScopeState(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.start(listOf(TourSection.DISPENSE, TourSection.DASHBOARD))
        state.advanceIfCurrent("x1")
        state.advanceIfCurrent("x2")
        state.continueToNextSection()
        assertEquals("pms", state.currentStep?.id)
        assertEquals(false, state.canGoBack)
        state.advanceIfCurrent("pms")
        assertEquals(true, state.canGoBack)
    }

    @Test
    fun `back does nothing while the next-section prompt is showing`() = runTest {
        val state = TestScopeState(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        state.start(listOf(TourSection.DISPENSE, TourSection.STOCK_COUNT))
        state.advanceIfCurrent("x1")
        state.advanceIfCurrent("x2")
        assertEquals(TourSection.STOCK_COUNT, state.pendingSection)
        val index = state.currentIndex

        state.back()

        assertEquals(index, state.currentIndex)
        state.continueToNextSection()
        assertEquals("s1", state.currentStep?.id)
    }

    @Test
    fun `end cancels a step that is still running`() = runTest {
        val state = TestScopeState(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val gate = CompletableDeferred<Unit>()
        var resumed = false
        state.start(listOf(TourSection.DISPENSE))
        state.runStep("x1") { gate.await(); resumed = true }
        state.end()
        gate.complete(Unit)
        assertFalse(resumed)
        assertFalse(state.stepBusy)
    }

    @Test
    fun `end cancels tracked demo jobs but track is a no-op outside a tour`() = runTest {
        val state = TestScopeState(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val idle = Job()
        state.track(idle)
        state.start(listOf(TourSection.DISPENSE))
        val demo = Job()
        state.track(demo)
        state.end()
        assertTrue(demo.isCancelled)
        assertFalse(idle.isCancelled)
    }

    @Test
    fun `end deactivates and notifies once even when called twice`() = runTest {
        val state = TestScopeState(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        var ended = 0
        state.onEnded = { ended++ }
        state.start(TourSection.entries)
        state.end()
        state.end()
        assertFalse(state.isActive)
        assertEquals(1, ended)
    }
}
