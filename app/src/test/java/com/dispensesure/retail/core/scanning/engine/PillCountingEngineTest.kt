package com.dispensesure.retail.core.scanning.engine

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import com.dispensesure.retail.R
import com.dispensesure.retail.core.room.dao.UserDao
import com.dispensesure.retail.core.scanning.domain.model.DetectedPill
import com.dispensesure.retail.core.scanning.engine.CountConfirmer.Companion.COUNT_CONFIRM_FRAMES
import com.dispensesure.retail.core.scanning.engine.IdleWatchdog.Companion.IDLE_STEP_MS
import com.dispensesure.retail.core.scanning.engine.IdleWatchdog.Companion.IDLE_TIMEOUT_MS
import com.dispensesure.retail.core.scanning.engine.PillCountingEngine.Companion.ADD_COOLDOWN_MS
import com.dispensesure.retail.core.scanning.logic.CameraHelper
import com.dispensesure.retail.core.scanning.logic.PillDetectionModelLoader
import com.dispensesure.retail.core.scanning.logic.TrayColor
import com.dispensesure.retail.core.utils.common.HelperFunctions
import com.dispensesure.retail.core.utils.common.LocationProvider
import com.dispensesure.retail.core.utils.common.OverlayUtils
import com.dispensesure.retail.core.utils.common.SoundUtils
import com.dispensesure.retail.core.utils.logger.PerformanceLogger
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.util.MainDispatcherRule
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [PillCountingEngine]: frame routing, idle watchdog, Add guards and photo,
 * gloves, hazardous tray, pause controls. The engine runs on the test Main dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PillCountingEngineTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context: Context = mockk(relaxed = true)
    private val modelLoader: PillDetectionModelLoader = mockk(relaxed = true)
    private val performanceLogger: PerformanceLogger = mockk(relaxed = true)
    private val locationProvider: LocationProvider = mockk(relaxed = true)
    private val userDao: UserDao = mockk(relaxed = true)
    private val preferenceHelper: PreferenceHelper = mockk(relaxed = true)

    private lateinit var engineScope: CoroutineScope
    private lateinit var engine: PillCountingEngine

    private val label = PhotoLabel(
        drugName = "Amoxicillin 500 mg",
        drugImagePath = null,
        isStockCount = false,
        requestedNdc = "11111-1111-11",
        dispensedNdc = "22222-2222-22",
        lotNumber = "L1",
        serialNumber = "S1",
        expirationDate = "04-30-2028",
        rxRefill = "123-2",
        stepLabel = "TARGET_VERIFICATION",
    )

    @Before
    fun setup() {
        every { context.getString(any<Int>()) } returns "Location unavailable"
        coJustRun { performanceLogger.logPerformanceSnapshot(any()) }
        // Relaxed String? stubs return "", which reads as a saved colour; force none.
        every { preferenceHelper.getHazardousTrayColor() } returns null
        engineScope = CoroutineScope(SupervisorJob() + mainDispatcherRule.testDispatcher)
        engine = PillCountingEngine(
            parentScope = engineScope,
            context = context,
            modelLoader = modelLoader,
            performanceLogger = performanceLogger,
            locationProvider = locationProvider,
            userDao = userDao,
            preferenceHelper = preferenceHelper,
        )
    }

    @After
    fun tearDown() {
        engineScope.cancel()
        unmockkAll()
    }

    private fun TestScope.collectEvents(): List<EngineEvent> {
        val reportedEvents = mutableListOf<EngineEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { engine.events.toList(reportedEvents) }
        return reportedEvents
    }

    private fun TestScope.collectAddPops(): List<Int> {
        val poppedCounts = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { engine.addPops.toList(poppedCounts) }
        return poppedCounts
    }

    private fun captureWithFrame(frame: Bitmap = mockk(relaxed = true), count: Int = 5): CaptureResult.Captured {
        engine.putFrameForTest(frame)
        return engine.captureCount(count) as CaptureResult.Captured
    }

    /** No placeholder drawable, file writes return "/photos/<name>", the strip drawing returns its input. */
    private fun stubPhotoWrites() {
        mockkStatic(ContextCompat::class)
        every { ContextCompat.getDrawable(any(), any()) } returns null
        mockkObject(HelperFunctions)
        every { HelperFunctions.saveBitmapToFile(any(), any(), any(), any(), any()) } answers {
            val fileName = thirdArg<String>()
            "/photos/$fileName"
        }
        mockkObject(OverlayUtils)
        every { OverlayUtils.drawDetectionsOnBitmap(any(), any(), any(), any(), any(), any()) } answers { firstArg() }
    }

    // ─────────────────────────── model loading ───────────────────────────

    @Test
    fun `start loads the pill and tray models without the glove model, once`() = runTest {
        engine.start()
        runCurrent()
        engine.start()
        runCurrent()

        coVerify(exactly = 1) { modelLoader.getOrLoadInterpreters(includeGlove = false) }
    }

    @Test
    fun `loadGloveModel loads the glove model`() = runTest {
        engine.loadGloveModel()
        runCurrent()

        coVerify { modelLoader.getOrLoadInterpreters(includeGlove = true) }
    }

    // ─────────────────────────── onFrame short-circuit paths ───────────────────────────

    // SCAN_VM_014
    @Test
    fun `onFrame closes the image when the model is not ready`() = runTest {
        val image = mockk<ImageProxy>(relaxed = true)

        engine.onFrame(image)

        verify { image.close() }
    }

    // SCAN_VM_015
    @Test
    fun `onFrame closes the image while detection is paused`() = runTest {
        val image = mockk<ImageProxy>(relaxed = true)
        engine.pauseDetection()

        engine.onFrame(image)

        verify { image.close() }
    }

    // ─────────────────────────── idle watchdog ───────────────────────────
    // The overlay must only fire on a genuinely static scene. A changing pill
    // count is active counting and has to keep pushing the deadline forward.

    // SCAN_VM_039
    @Test
    fun `count activity inside the window keeps the idle overlay hidden past the timeout`() = runTest {
        advanceTimeBy(IDLE_TIMEOUT_MS / 2)
        engine.noteActivity()
        advanceTimeBy(IDLE_TIMEOUT_MS)

        assertFalse(engine.state.value.showIdleOverlay)
        assertFalse(engine.state.value.isCameraPaused)
    }

    // SCAN_VM_040
    @Test
    fun `no count activity for a full window shows the idle overlay and pauses the camera`() = runTest {
        advanceTimeBy(IDLE_TIMEOUT_MS + 1_000)

        assertTrue(engine.state.value.showIdleOverlay)
        assertTrue(engine.state.value.isCameraPaused)
    }

    // SCAN_VM_041
    @Test
    fun `stopIdleTimer stops the overlay from ever firing`() = runTest {
        engine.stopIdleTimer()
        advanceTimeBy(IDLE_TIMEOUT_MS * 3)

        assertFalse(engine.state.value.showIdleOverlay)
    }

    // SCAN_VM_042
    @Test
    fun `resumeFromIdle re-arms the watchdog so it can fire again`() = runTest {
        advanceTimeBy(IDLE_TIMEOUT_MS + 1_000)
        assertTrue(engine.state.value.showIdleOverlay)

        engine.resumeFromIdle()
        assertFalse(engine.state.value.showIdleOverlay)
        assertFalse(engine.state.value.isCameraPaused)

        advanceTimeBy(IDLE_TIMEOUT_MS + 1_000)
        assertTrue(engine.state.value.showIdleOverlay)
    }

    // SCAN_VM_043
    @Test
    fun `an accepted capture re-arms the watchdog`() = runTest {
        advanceTimeBy(IDLE_TIMEOUT_MS / 2)
        captureWithFrame()
        advanceTimeBy(IDLE_TIMEOUT_MS)

        assertFalse(engine.state.value.showIdleOverlay)
    }

    // SCAN_VM_044
    @Test
    fun `a single early activity tick does not delay the pause by a whole extra window`() = runTest {
        advanceTimeBy(3_000)
        engine.noteActivity()

        // Deadline is ~3s + timeout, not ~2x timeout.
        advanceTimeBy(IDLE_TIMEOUT_MS + IDLE_STEP_MS + 1_000)

        assertTrue(engine.state.value.showIdleOverlay)
    }

    // SCAN_VM_045
    @Test
    fun `count flicker that never holds still does not hold off the pause`() = runTest {
        // A still tray whose detector alternates 11 and 12 every frame.
        repeat(40) { frameIndex ->
            engine.feedCountFrameForTest(if (frameIndex % 2 == 0) 11 else 12)
            advanceTimeBy(2_000)
        }

        assertTrue(engine.state.value.showIdleOverlay)
    }

    // SCAN_VM_046
    @Test
    fun `a count held for the confirm window counts as activity`() = runTest {
        advanceTimeBy(IDLE_TIMEOUT_MS / 2)
        repeat(COUNT_CONFIRM_FRAMES) { engine.feedCountFrameForTest(11) }
        advanceTimeBy(IDLE_TIMEOUT_MS)

        assertFalse(engine.state.value.showIdleOverlay)
    }

    // SCAN_VM_047
    @Test
    fun `a confirmed count that then goes still still pauses`() = runTest {
        repeat(COUNT_CONFIRM_FRAMES) { engine.feedCountFrameForTest(11) }
        // Same count keeps arriving — confirmed once, so no further activity.
        repeat(20) { engine.feedCountFrameForTest(11) }
        advanceTimeBy(IDLE_TIMEOUT_MS + IDLE_STEP_MS + 1_000)

        assertTrue(engine.state.value.showIdleOverlay)
    }

    @Test
    fun `the idle pause swaps in a new empty recent-counts deque`() = runTest {
        engine.processDetections(
            count = 3, detections = emptyList(), trayDetections = emptyList(), gloveDetections = emptyList(),
            bitmap = mockk(relaxed = true), imageWidth = 1, imageHeight = 1,
        )
        val countsBeforePause = engine.lastTenDetections.value

        advanceTimeBy(IDLE_TIMEOUT_MS + 1_000)

        assertTrue(engine.lastTenDetections.value.isEmpty())
        assertNotSame(countsBeforePause, engine.lastTenDetections.value)
        assertEquals(listOf(3), countsBeforePause.toList())
    }

    @Test
    fun `resumeLiveCounting unpauses, hides the idle overlay and re-arms the watchdog`() = runTest {
        advanceTimeBy(IDLE_TIMEOUT_MS + 1_000)
        assertTrue(engine.state.value.showIdleOverlay)

        engine.resumeLiveCounting()
        assertFalse(engine.state.value.showIdleOverlay)
        assertFalse(engine.state.value.isCameraPaused)

        advanceTimeBy(IDLE_TIMEOUT_MS + 1_000)
        assertTrue(engine.state.value.showIdleOverlay)
    }

    // ─────────────────────────── filtered pills ───────────────────────────

    // SCAN_VM_029
    @Test
    fun `onFilteredPills with a new list updates filteredPills`() = runTest {
        val newPills = listOf(DetectedPill(x = 10f, y = 20f, confidence = 0.9f))

        engine.onFilteredPills(newPills)

        assertEquals(newPills, engine.state.value.filteredPills)
    }

    // SCAN_VM_030
    @Test
    fun `onFilteredPills with the same list reference is a no-op`() = runTest {
        val pills = listOf(DetectedPill(x = 10f, y = 20f, confidence = 0.9f))
        engine.onFilteredPills(pills)
        val stateBeforeSecondCall = engine.state.value

        engine.onFilteredPills(pills)

        assertSame(stateBeforeSecondCall, engine.state.value)
    }

    // ─────────────────────────── count sound ───────────────────────────

    @Test
    fun `playCountSoundIfEnabled routes the count cue through SoundUtils`() {
        mockkObject(SoundUtils)
        every { SoundUtils.playCountSound(any()) } returns Unit
        every { preferenceHelper.isSoundEnabled() } returns true
        every { preferenceHelper.isHapticEnabled() } returns false

        engine.playCountSoundIfEnabled()

        verify(exactly = 1) { SoundUtils.playCountSound(any()) }
    }

    @Test
    fun `playCountSoundIfEnabled stays silent when the sound preference is off`() {
        mockkObject(SoundUtils)
        every { SoundUtils.playCountSound(any()) } returns Unit
        every { preferenceHelper.isSoundEnabled() } returns false
        every { preferenceHelper.isHapticEnabled() } returns false

        engine.playCountSoundIfEnabled()

        verify(exactly = 0) { SoundUtils.playCountSound(any()) }
    }

    // ─────────────────────────── captureCount guards ───────────────────────────

    @Test
    fun `a zero count is rejected and leaves the frame in place`() = runTest {
        val frame = mockk<Bitmap>(relaxed = true)
        engine.putFrameForTest(frame)

        val result = engine.captureCount(0)

        assertEquals(CaptureResult.Rejected(CaptureResult.Reason.ZeroCount), result)
        assertSame(frame, engine.frameForTest())
        assertFalse(engine.state.value.isAddCooldown)
    }

    @Test
    fun `no camera frame is rejected without starting the cooldown`() = runTest {
        val result = engine.captureCount(5)

        assertEquals(CaptureResult.Rejected(CaptureResult.Reason.NoFrame), result)
        assertFalse(engine.state.value.isAddCooldown)
        // Signature unset, so the retry isn't rejected as a duplicate.
        assertNull(engine.lastAddSignatureForTest())
    }

    @Test
    fun `a capture takes the frame at the tap, pops +N and starts the cooldown`() = runTest {
        val poppedCounts = collectAddPops()
        val frame = mockk<Bitmap>(relaxed = true)

        val captured = captureWithFrame(frame, count = 5)

        assertSame(frame, captured.frame)
        assertEquals(5, captured.count)
        assertNull(engine.frameForTest())
        assertTrue(engine.state.value.isAddCooldown)
        assertEquals(listOf(5), poppedCounts)
    }

    @Test
    fun `a capture keeps the pills and trays shown at the tap`() = runTest {
        val pills = listOf(DetectedPill(x = 0.5f, y = 0.5f, confidence = 0.9f))
        engine.onFilteredPills(pills)

        val captured = captureWithFrame()

        assertEquals(pills, captured.filteredPills)
        assertEquals(engine.state.value.trayDetections, captured.trays)
    }

    @Test
    fun `a second tap inside the cooldown is rejected`() = runTest {
        captureWithFrame(count = 5)
        engine.putFrameForTest(mockk(relaxed = true))

        val result = engine.captureCount(6)

        assertEquals(CaptureResult.Rejected(CaptureResult.Reason.Cooldown), result)
    }

    @Test
    fun `the cooldown ends after ADD_COOLDOWN_MS`() = runTest {
        captureWithFrame()
        advanceTimeBy(ADD_COOLDOWN_MS + 1)

        assertFalse(engine.state.value.isAddCooldown)
    }

    @Test
    fun `the same pills and count again is a duplicate and its frame is recycled`() = runTest {
        captureWithFrame(count = 5)
        advanceTimeBy(ADD_COOLDOWN_MS + 1)
        val secondFrame = mockk<Bitmap>(relaxed = true)
        engine.putFrameForTest(secondFrame)

        val result = engine.captureCount(5)

        assertEquals(CaptureResult.Rejected(CaptureResult.Reason.Duplicate), result)
        verify { secondFrame.recycle() }
    }

    @Test
    fun `a duplicate Add does not pop +N`() = runTest {
        val poppedCounts = collectAddPops()
        captureWithFrame(count = 5)
        advanceTimeBy(ADD_COOLDOWN_MS + 1)
        engine.putFrameForTest(mockk(relaxed = true))

        engine.captureCount(5)

        assertEquals(listOf(5), poppedCounts)
    }

    @Test
    fun `a different count on the same pills is not a duplicate`() = runTest {
        captureWithFrame(count = 5)
        advanceTimeBy(ADD_COOLDOWN_MS + 1)
        engine.putFrameForTest(mockk(relaxed = true))

        assertTrue(engine.captureCount(6) is CaptureResult.Captured)
    }

    @Test
    fun `resumeFromIdle forgets the last Add`() = runTest {
        captureWithFrame(count = 5)
        engine.resumeFromIdle()

        assertNull(engine.lastAddSignatureForTest())
    }

    // ─────────────────────────── saveCountPhoto ───────────────────────────

    @Test
    fun `saveCountPhoto passes grayscale to the writer when the colour setting is off`() = runTest {
        stubPhotoWrites()
        every { preferenceHelper.isGrayscaleImage() } returns true

        engine.saveCountPhoto(captureWithFrame(), label, keepRawCrop = false)

        verify { HelperFunctions.saveBitmapToFile(any(), any(), any(), any(), grayscale = true) }
    }

    // ─────────────────────────── gloves ───────────────────────────

    @Test
    fun `three strong glove frames in a row confirm gloves and report it once`() = runTest {
        val reportedEvents = collectEvents()

        repeat(3) { engine.feedGloveFrameForTest(confidence = 0.9f) }
        runCurrent()

        assertTrue(engine.state.value.glovesDetected)
        assertEquals(listOf<EngineEvent>(EngineEvent.GlovesConfirmed), reportedEvents)
        coVerify { modelLoader.unloadGloveModel() }
    }

    @Test
    fun `a weak frame breaks the glove run`() = runTest {
        engine.feedGloveFrameForTest(confidence = 0.9f)
        engine.feedGloveFrameForTest(confidence = 0.9f)
        engine.feedGloveFrameForTest(confidence = 0.5f)
        engine.feedGloveFrameForTest(confidence = 0.9f)

        assertFalse(engine.state.value.glovesDetected)
    }

    @Test
    fun `resetGloves clears confirmed gloves`() = runTest {
        repeat(3) { engine.feedGloveFrameForTest(confidence = 0.9f) }

        engine.resetGloves()

        assertFalse(engine.state.value.glovesDetected)
    }

    // ─────────────────────────── hazardous tray ───────────────────────────

    @Test
    fun `classifyTray saves the colour, clears the popup and reports the result once`() = runTest {
        val reportedEvents = collectEvents()

        engine.classifyTray(TrayColor.RED, isHazardous = true)
        engine.classifyTray(TrayColor.RED, isHazardous = true)

        verify { preferenceHelper.setHazardousTrayColor("RED") }
        assertNull(engine.state.value.pendingTrayColor)
        assertEquals(listOf<EngineEvent>(EngineEvent.HazardousTrayResult(true)), reportedEvents)
    }

    @Test
    fun `a tray result reported before anyone collects is still delivered`() = runTest {
        engine.classifyTray(TrayColor.RED, isHazardous = false)

        val reportedEvents = collectEvents()

        assertEquals(listOf<EngineEvent>(EngineEvent.HazardousTrayResult(false)), reportedEvents)
    }

    @Test
    fun `hazardous drug with the setting off reports false for a tray with no saved colour`() = runTest {
        every { preferenceHelper.isHazardousDrugEnabled() } returns false
        val reportedEvents = collectEvents()
        engine.configureHazardous(isHazardousDrug = true)

        engine.feedTrayFrameForTest(TrayColor.RED)

        assertEquals(listOf<EngineEvent>(EngineEvent.HazardousTrayResult(false)), reportedEvents)
    }

    @Test
    fun `hazardous drug with the setting on asks the user about a new tray colour`() = runTest {
        every { preferenceHelper.isHazardousDrugEnabled() } returns true
        engine.configureHazardous(isHazardousDrug = true)

        engine.feedTrayFrameForTest(TrayColor.RED)

        assertEquals(TrayColor.RED, engine.state.value.pendingTrayColor)
    }

    @Test
    fun `hazardous drug on the saved hazardous tray reports true`() = runTest {
        every { preferenceHelper.isHazardousDrugEnabled() } returns true
        every { preferenceHelper.getHazardousTrayColor() } returns "RED"
        val reportedEvents = collectEvents()
        engine.configureHazardous(isHazardousDrug = true)

        engine.feedTrayFrameForTest(TrayColor.RED)

        assertEquals(listOf<EngineEvent>(EngineEvent.HazardousTrayResult(true)), reportedEvents)
    }

    @Test
    fun `a non-hazardous drug on the hazardous tray sends one warning and saves nothing`() = runTest {
        every { preferenceHelper.getHazardousTrayColor() } returns "RED"
        val reportedEvents = collectEvents()
        engine.configureHazardous(isHazardousDrug = false)

        engine.feedTrayFrameForTest(TrayColor.RED)
        engine.feedTrayFrameForTest(TrayColor.RED)

        assertEquals(listOf<EngineEvent>(EngineEvent.Warning(R.string.hazardous_tray_warning)), reportedEvents)
    }

    @Test
    fun `a hazardous drug on another tray sends one swap-tray warning`() = runTest {
        every { preferenceHelper.isHazardousDrugEnabled() } returns true
        every { preferenceHelper.getHazardousTrayColor() } returns "RED"
        val reportedEvents = collectEvents()
        engine.configureHazardous(isHazardousDrug = true)

        engine.feedTrayFrameForTest(TrayColor.BLUE)
        engine.feedTrayFrameForTest(TrayColor.BLUE)

        assertEquals(listOf<EngineEvent>(EngineEvent.Warning(R.string.non_hazardous_tray_warning)), reportedEvents)
    }

    @Test
    fun `an idle pause closes the tray popup and Resume asks about the colour again`() = runTest {
        every { preferenceHelper.isHazardousDrugEnabled() } returns true
        engine.configureHazardous(isHazardousDrug = true)
        engine.feedTrayFrameForTest(TrayColor.RED)
        assertEquals(TrayColor.RED, engine.state.value.pendingTrayColor)

        advanceTimeBy(IDLE_TIMEOUT_MS + 1_000)
        assertNull(engine.state.value.pendingTrayColor)

        engine.resumeFromIdle()
        engine.feedTrayFrameForTest(TrayColor.RED)
        assertEquals(TrayColor.RED, engine.state.value.pendingTrayColor)
    }

    // ─────────────────────────── pause controls ───────────────────────────

    @Test
    fun `pauseDetection clears pills and trays`() = runTest {
        engine.feedTrayFrameForTest(TrayColor.RED)

        engine.pauseDetection()

        assertTrue(engine.state.value.detectedPills.isEmpty())
        assertTrue(engine.state.value.trayDetections.isEmpty())
    }

    @Test
    fun `setCameraPaused true pauses the camera and false resumes it`() = runTest {
        engine.setCameraPaused(true)
        assertTrue(engine.state.value.isCameraPaused)

        engine.setCameraPaused(false)
        assertFalse(engine.state.value.isCameraPaused)
    }

    @Test
    fun `paused detection drops analyzed frames`() = runTest {
        engine.pauseDetection()
        val frame = mockk<Bitmap>(relaxed = true)

        engine.processDetections(
            count = 3, detections = emptyList(), trayDetections = emptyList(), gloveDetections = emptyList(),
            bitmap = frame, imageWidth = 1, imageHeight = 1,
        )

        verify { frame.recycle() }
        assertNull(engine.frameForTest())
    }

    // ─────────────────────────── still photo, close ───────────────────────────

    @Test
    fun `captureStill without a camera returns false`() {
        assertFalse(engine.captureStill(onCaptured = {}, onError = {}))
    }

    @Test
    fun `captureStill asks the attached camera`() {
        val cameraHelper: CameraHelper = mockk(relaxed = true)
        every { cameraHelper.captureImage(any(), any()) } returns true
        engine.attachCamera(cameraHelper)

        assertTrue(engine.captureStill(onCaptured = {}, onError = {}))
        verify(exactly = 1) { cameraHelper.captureImage(any(), any()) }
    }

    @Test
    fun `attachCamera starts a location lookup`() = runTest {
        engine.attachCamera(mockk(relaxed = true))

        coVerify(timeout = 3000) { locationProvider.getCurrentLocationAsString() }
    }

    @Test
    fun `close writes the performance summary`() {
        engine.close()

        verify { performanceLogger.generateSummaryReport() }
    }

    @Test
    fun `close stops the idle watchdog but leaves the parent scope running`() = runTest {
        engine.close()
        advanceTimeBy(IDLE_TIMEOUT_MS * 2)

        assertFalse(engine.state.value.showIdleOverlay)
        assertTrue(engineScope.isActive)
    }
}
