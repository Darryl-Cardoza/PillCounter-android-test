package com.dispensesure.retail.core.health.logic

import android.util.Log
import com.dispensesure.retail.core.audit.AuditLogger
import com.dispensesure.retail.core.audit.model.OfflineEndedBy
import com.dispensesure.retail.core.audit.model.OfflineModeEntered
import com.dispensesure.retail.core.audit.model.OfflineModeExited
import com.dispensesure.retail.core.audit.model.OfflineTrigger
import com.dispensesure.retail.core.audit.model.ServerConfigChanged
import com.dispensesure.retail.core.audit.model.ServerSetting
import com.dispensesure.retail.core.health.data.remote.dto.HealthCheckData
import com.dispensesure.retail.core.health.domain.data.IHealthRepository
import com.dispensesure.retail.core.health.domain.model.HealthState
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.feature.hl7.data.repository.DispenseHl7Repository
import com.dispensesure.retail.feature.hl7.data.repository.StockHl7Repository
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for [SessionHealthController].
 *
 * Covers:
 *  - checkHealth outcomes (success, failure, throttle, force)
 *  - classifyAndReact routing
 *  - evaluateExpiry with lastHealthAt / loggedInAt fallback anchor
 *  - updateThreshold guard
 *  - onConnectivityRestored force-flag
 *  - markLoggedIn / markLoggedOut
 *  - beginTeardown / endTeardown idempotency
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionHealthControllerTest {

    private lateinit var healthRepository: IHealthRepository
    private lateinit var preferenceHelper: PreferenceHelper
    private lateinit var dispenseHl7Repository: DispenseHl7Repository
    private lateinit var stockHl7Repository: StockHl7Repository

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        healthRepository = mockk()
        preferenceHelper = mockk(relaxed = true)
        dispenseHl7Repository = mockk(relaxed = true)
        stockHl7Repository = mockk(relaxed = true)
        every { preferenceHelper.getLastHealthCheckedAt() } returns 0L
        every { preferenceHelper.getOfflineSessionThresholdSeconds() } returns 60L
        every { preferenceHelper.getLoggedInAt() } returns 0L
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private val auditLogger = mockk<AuditLogger>(relaxed = true)

    private fun newController(): SessionHealthController =
        SessionHealthController(
            healthRepository,
            preferenceHelper,
            Lazy { dispenseHl7Repository },
            Lazy { stockHl7Repository },
            auditLogger,
        )

    // ─────────────────────── offline periods (audit) ───────────────────────

    @Test
    fun offline_isLoggedOnTheTransitionOnly_withItsTrigger() = runTest {
        coEvery { healthRepository.checkHealth() } returns Result.failure(IOException("no net"))
        val controller = newController()

        controller.checkHealth(force = true, trigger = OfflineTrigger.APP_RESUMED)
        controller.checkHealth(force = true, trigger = OfflineTrigger.REQUEST_FAILED)

        verify(exactly = 1) { auditLogger.log(match { it is OfflineModeEntered }) }
        verify { auditLogger.log(match { it is OfflineModeEntered && it.trigger == OfflineTrigger.APP_RESUMED }) }
        verify { preferenceHelper.setOfflineStartedAt(any()) }
    }

    @Test
    fun backOnline_closesTheOpenPeriod() = runTest {
        coEvery { healthRepository.checkHealth() } returns Result.failure(IOException("no net"))
        val controller = newController()
        controller.checkHealth(force = true)
        every { preferenceHelper.getOfflineStartedAt() } returns System.currentTimeMillis() - 5_000L
        coEvery { healthRepository.checkHealth() } returns Result.success(
            HealthCheckData(isHealthy = true, checks = null, checkedAt = "2026-08-24T12:00:00Z")
        )

        controller.checkHealth(force = true)

        verify { auditLogger.log(match { it is OfflineModeExited && it.endedBy == OfflineEndedBy.BACK_ONLINE }) }
        verify { preferenceHelper.clearOfflineStartedAt() }
    }

    @Test
    fun expiry_closesThePeriodAsLimitReached() {
        every { preferenceHelper.getLastHealthCheckedAt() } returns System.currentTimeMillis() - 120_000L
        every { preferenceHelper.getOfflineStartedAt() } returns System.currentTimeMillis() - 60_000L
        val controller = newController()
        controller.forceStateForTest(HealthState.OFFLINE)

        assertTrue(controller.evaluateExpiry())

        verify { auditLogger.log(match { it is OfflineModeExited && it.endedBy == OfflineEndedBy.OFFLINE_LIMIT_REACHED }) }
    }

    @Test
    fun logout_closesAnOpenPeriod_andLogsNothingWhenNoneIsOpen() {
        every { preferenceHelper.getOfflineStartedAt() } returns 0L
        val controller = newController()
        controller.markLoggedOut()
        verify(exactly = 0) { auditLogger.log(match { it is OfflineModeExited }) }

        every { preferenceHelper.getOfflineStartedAt() } returns System.currentTimeMillis() - 1_000L
        controller.markLoggedOut()
        verify { auditLogger.log(match { it is OfflineModeExited && it.endedBy == OfflineEndedBy.LOGGED_OUT }) }
    }

    @Test
    fun markLoggedOut_resetsOfflineState_soNextDropIsLogged() = runTest {
        coEvery { healthRepository.checkHealth() } returns Result.failure(IOException("no net"))
        val controller = newController()
        controller.checkHealth(force = true)

        controller.markLoggedOut()

        assertEquals(HealthState.UNKNOWN, controller.state.value)
        controller.checkHealth(force = true)
        verify(exactly = 2) { auditLogger.log(match { it is OfflineModeEntered }) }
    }

    @Test
    fun aPeriodLeftOpenByAKill_isClosedAtStartup() {
        every { preferenceHelper.getOfflineStartedAt() } returns System.currentTimeMillis() - 30_000L

        newController()

        verify(timeout = 2_000) {
            auditLogger.log(match { it is OfflineModeExited && it.endedBy == OfflineEndedBy.APP_RESTARTED })
        }
    }

    @Test
    fun updateThreshold_logsOnlyARealChange() {
        val controller = newController()

        controller.updateThreshold(60L)
        controller.updateThreshold(120L)

        verify(exactly = 1) { auditLogger.log(match { it is ServerConfigChanged }) }
        verify { auditLogger.log(ServerConfigChanged(ServerSetting.OFFLINE_LIMIT_SECONDS, "60", "120")) }
    }

    // ─────────────────────── checkHealth ───────────────────────

    @Test
    fun checkHealth_success_flipsStateHealthyAndPersistsCheckedAt() = runTest {
        coEvery { healthRepository.checkHealth() } returns Result.success(
            HealthCheckData(isHealthy = true, checks = null, checkedAt = "2026-08-24T12:00:00Z")
        )
        val controller = newController()

        val ok = controller.checkHealth()

        assertTrue(ok)
        assertEquals(HealthState.HEALTHY, controller.state.value)
        verify { preferenceHelper.setLastHealthCheckedAt(any()) }
    }

    @Test
    fun checkHealth_success_withUnparseableCheckedAt_fallsBackToDeviceClock() = runTest {
        coEvery { healthRepository.checkHealth() } returns Result.success(
            HealthCheckData(isHealthy = true, checks = null, checkedAt = "not-a-timestamp")
        )
        val before = System.currentTimeMillis()
        val controller = newController()

        controller.checkHealth()

        assertTrue(controller.lastHealthAt.value >= before)
    }

    @Test
    fun checkHealth_failure_flipsStateOffline() = runTest {
        coEvery { healthRepository.checkHealth() } returns Result.failure(IOException("no net"))
        val controller = newController()

        val ok = controller.checkHealth()

        assertFalse(ok)
        assertEquals(HealthState.OFFLINE, controller.state.value)
    }

    @Test
    fun checkHealth_throttled_returnsCachedResultWithoutSecondNetworkHit() = runTest {
        coEvery { healthRepository.checkHealth() } returns Result.failure(IOException("no net"))
        val controller = newController()

        controller.checkHealth()          // prime cache
        controller.checkHealth()          // second call within throttle window

        coVerify(atMost = 2) { healthRepository.checkHealth() } // MAX_ATTEMPTS in one call = 2; second call cached
    }

    @Test
    fun checkHealth_force_bypassesThrottle() = runTest {
        coEvery { healthRepository.checkHealth() } returns Result.failure(IOException("no net"))
        val controller = newController()

        controller.checkHealth()               // prime cache
        controller.checkHealth(force = true)   // must hit network again

        // First call = 2 attempts (retry), second (forced) = 2 more attempts -> total 4
        coVerify(atLeast = 3) { healthRepository.checkHealth() }
    }

    // ─────────────────────── classifyAndReact ───────────────────────

    @Test
    fun classifyAndReact_ioException_triggersCheckHealth() = runTest {
        coEvery { healthRepository.checkHealth() } returns Result.success(
            HealthCheckData(isHealthy = true, checks = null, checkedAt = "2026-08-24T12:00:00Z")
        )
        val controller = newController()

        controller.classifyAndReact(IOException("net"), null)
        // Give the launched scope a tick — controller uses its own dispatcher so we cannot
        // control it precisely; still, mockk records eventual invocations.
        Thread.sleep(200)

        coVerify(atLeast = 1) { healthRepository.checkHealth() }
    }

    @Test
    fun classifyAndReact_5xx_triggersCheckHealth() = runTest {
        coEvery { healthRepository.checkHealth() } returns Result.success(
            HealthCheckData(isHealthy = true, checks = null, checkedAt = "2026-08-24T12:00:00Z")
        )
        val controller = newController()

        controller.classifyAndReact(null, 502)
        Thread.sleep(200)

        coVerify(atLeast = 1) { healthRepository.checkHealth() }
    }

    @Test
    fun classifyAndReact_401_isIgnored() = runTest {
        val controller = newController()

        controller.classifyAndReact(null, 401)
        Thread.sleep(100)

        coVerify(exactly = 0) { healthRepository.checkHealth() }
    }

    @Test
    fun classifyAndReact_2xx_isIgnored() = runTest {
        val controller = newController()

        controller.classifyAndReact(null, 200)
        Thread.sleep(100)

        coVerify(exactly = 0) { healthRepository.checkHealth() }
    }

    // ─────────────────────── evaluateExpiry ───────────────────────

    @Test
    fun evaluateExpiry_notOffline_returnsFalse() {
        val controller = newController()
        // Default state is UNKNOWN
        assertFalse(controller.evaluateExpiry())
    }

    @Test
    fun evaluateExpiry_offlineButBothAnchorsZero_returnsFalse() {
        val controller = newController()
        controller.forceStateForTest(HealthState.OFFLINE)

        assertFalse(controller.evaluateExpiry())
    }

    @Test
    fun evaluateExpiry_offlineWithHealthAnchor_withinThreshold_returnsFalse() {
        every { preferenceHelper.getLastHealthCheckedAt() } returns System.currentTimeMillis() - 5_000L
        every { preferenceHelper.getOfflineSessionThresholdSeconds() } returns 60L
        val controller = newController()
        controller.forceStateForTest(HealthState.OFFLINE)

        assertFalse(controller.evaluateExpiry())
    }

    @Test
    fun evaluateExpiry_offlineWithHealthAnchor_beyondThreshold_flipsExpired() {
        every { preferenceHelper.getLastHealthCheckedAt() } returns System.currentTimeMillis() - 120_000L
        every { preferenceHelper.getOfflineSessionThresholdSeconds() } returns 60L
        val controller = newController()
        controller.forceStateForTest(HealthState.OFFLINE)

        assertTrue(controller.evaluateExpiry())
        assertEquals(HealthState.EXPIRED, controller.state.value)
    }

    @Test
    fun evaluateExpiry_offlineWithLoggedInAnchor_beyondThreshold_flipsExpired() {
        every { preferenceHelper.getLastHealthCheckedAt() } returns 0L
        every { preferenceHelper.getLoggedInAt() } returns System.currentTimeMillis() - 120_000L
        every { preferenceHelper.getOfflineSessionThresholdSeconds() } returns 60L
        val controller = newController()
        controller.forceStateForTest(HealthState.OFFLINE)

        assertTrue(controller.evaluateExpiry())
        assertEquals(HealthState.EXPIRED, controller.state.value)
    }

    @Test
    fun evaluateExpiry_offlineWithLoggedInAnchor_withinThreshold_returnsFalse() {
        every { preferenceHelper.getLastHealthCheckedAt() } returns 0L
        every { preferenceHelper.getLoggedInAt() } returns System.currentTimeMillis() - 10_000L
        every { preferenceHelper.getOfflineSessionThresholdSeconds() } returns 60L
        val controller = newController()
        controller.forceStateForTest(HealthState.OFFLINE)

        assertFalse(controller.evaluateExpiry())
    }

    // ─────────────────────── updateThreshold ───────────────────────

    @Test
    fun updateThreshold_positive_persistedAndFlowed() {
        val controller = newController()

        controller.updateThreshold(seconds = 30L)

        verify { preferenceHelper.setOfflineSessionThresholdSeconds(30L) }
        assertEquals(30_000L, controller.thresholdMs.value)
    }

    @Test
    fun updateThreshold_nonPositive_ignored() {
        val controller = newController()
        val prev = controller.thresholdMs.value

        controller.updateThreshold(seconds = 0L)
        controller.updateThreshold(seconds = -5L)

        assertEquals(prev, controller.thresholdMs.value)
        verify(exactly = 0) { preferenceHelper.setOfflineSessionThresholdSeconds(any()) }
    }

    // ─────────────────────── markLoggedIn / markLoggedOut ───────────────────────

    @Test
    fun markLoggedIn_writesToPrefsAndFlow() {
        val controller = newController()

        controller.markLoggedIn(nowMs = 42L)

        verify { preferenceHelper.setLoggedInAt(42L) }
        assertEquals(42L, controller.loggedInAt.value)
    }

    @Test
    fun markLoggedOut_clearsPrefsAndFlow() {
        every { preferenceHelper.getLoggedInAt() } returns 100L
        val controller = newController()

        controller.markLoggedOut()

        verify { preferenceHelper.clearLoggedInAt() }
        assertEquals(0L, controller.loggedInAt.value)
    }

    // ─────────────────────── beginTeardown / endTeardown ───────────────────────

    @Test
    fun beginTeardown_isOneShotUntilEndTeardown() {
        val controller = newController()

        assertTrue(controller.beginTeardown())
        assertFalse(controller.beginTeardown())
        controller.endTeardown()
        assertTrue(controller.beginTeardown())
    }
}
