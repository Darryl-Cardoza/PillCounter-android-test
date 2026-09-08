package com.rite.pillcounting.core.faceAuth.logic

import com.rite.pillcounting.core.faceAuth.data.FaceProfileRepository
import com.rite.pillcounting.core.room.models.FaceProfileEntity
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the lock/unlock contract [com.rite.pillcounting.MainActivity] drives:
 * the post-login `startOnScan` path and the fresh-launch re-arm.
 *
 * Fixtures keep `getFaceLockTimeoutMinutes` well above the test's lifetime so the
 * controller's 1s idle tick can never fire mid-assertion, and keep
 * `hasEnabledFaceProfile` consistent with the mocked profile list so the eagerly
 * shared `hasEnabledProfile` flow cannot race the synchronous lock calls.
 */
class SessionLockControllerTest {

    private fun controller(
        hasEnabledProfile: Boolean,
        isLoggedIn: Boolean = false
    ): SessionLockController {
        val preferenceHelper = mockk<PreferenceHelper>(relaxed = true) {
            every { isUserLoggedIn() } returns isLoggedIn
            every { hasEnabledFaceProfile() } returns hasEnabledProfile
            // Far longer than any test runs, so tick() can't lock behind our back.
            every { getFaceLockTimeoutMinutes() } returns 60
        }
        val profiles = if (hasEnabledProfile) {
            listOf(FaceProfileEntity(id = 1L, firstName = "Bruce", lastName = "Wayne", email = null, createdAt = 0L))
        } else {
            emptyList()
        }
        val repository = mockk<FaceProfileRepository> {
            every { observeProfiles() } returns flowOf(profiles)
        }
        return SessionLockController(preferenceHelper, repository)
    }

    @Test
    fun `lockNow with startOnScan locks and opens on the verify camera`() {
        val controller = controller(hasEnabledProfile = true)

        assertTrue(controller.lockNow(startOnScan = true))

        assertTrue(controller.isLocked.value)
        assertTrue(controller.startOnScan.value)
    }

    @Test
    fun `lockNow without startOnScan locks on the idle lock screen`() {
        val controller = controller(hasEnabledProfile = true)

        assertTrue(controller.lockNow())

        assertTrue(controller.isLocked.value)
        assertFalse(controller.startOnScan.value)
    }

    @Test
    fun `unlock clears startOnScan so a later idle lock still shows the lock screen`() {
        val controller = controller(hasEnabledProfile = true)
        controller.lockNow(startOnScan = true)

        controller.unlock()

        assertFalse(controller.isLocked.value)
        assertFalse(controller.startOnScan.value)
    }

    @Test
    fun `lockNow with no enabled profile does not lock`() {
        val controller = controller(hasEnabledProfile = false)

        assertFalse(controller.lockNow(startOnScan = true))

        assertFalse(controller.isLocked.value)
        assertFalse(controller.startOnScan.value)
    }

    /**
     * The reported bug: a foreground service keeps the process alive across a
     * swipe-away, so this singleton survives already-unlocked. A fresh launch has
     * to re-lock it — the constructor's cold-start check never runs again.
     */
    @Test
    fun `onAppLaunch re-locks a surviving unlocked session`() {
        val controller = controller(hasEnabledProfile = true, isLoggedIn = true)
        controller.unlock()
        assertFalse(controller.isLocked.value)

        assertTrue(controller.onAppLaunch())

        assertTrue(controller.isLocked.value)
        // A relaunch gets the idle lock screen; only a fresh login opens on the camera.
        assertFalse(controller.startOnScan.value)
    }

    @Test
    fun `onAppLaunch does nothing when nobody is logged in`() {
        val controller = controller(hasEnabledProfile = true, isLoggedIn = false)

        assertFalse(controller.onAppLaunch())

        assertFalse(controller.isLocked.value)
    }

    @Test
    fun `onAppLaunch does nothing with no enabled profile`() {
        val controller = controller(hasEnabledProfile = false, isLoggedIn = true)

        assertFalse(controller.onAppLaunch())

        assertFalse(controller.isLocked.value)
    }
}
