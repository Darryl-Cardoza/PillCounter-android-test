package com.rite.pillcounting.core.faceAuth.logic

import com.rite.pillcounting.core.faceAuth.data.FaceProfileRepository
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives the app-wide idle/session lock: tracks user activity, decides when to
 * lock, and exposes whether the app is currently locked.
 *
 * Description:
 * The face-recognition base engine deliberately deferred this idle-timeout
 * auto-trigger to a later phase (see the original design doc); this is that
 * phase. A single app-process-lifetime instance ticks once a second, comparing
 * elapsed idle time against the configured timeout — but only ever arms the
 * lock while at least one enrolled face profile is enabled, so a device with
 * no Quick Access set up never locks itself out with nothing to verify against.
 *
 * What it does:
 * - [onUserActivity] resets the idle clock; call on every touch app-wide.
 * - [isLocked] is the single source of truth the UI observes to show/hide the overlay.
 * - [lockNow] lets Settings trigger the same overlay on demand.
 * - [unlock] is called after a successful face verify.
 */
@Singleton
class SessionLockController @Inject constructor(
    private val preferenceHelper: PreferenceHelper,
    faceProfileRepository: FaceProfileRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lastActivityAt = AtomicLong(System.currentTimeMillis())

    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    /** Whether at least one enrolled face profile currently participates in verify matching. */
    val hasEnabledProfile: StateFlow<Boolean> = faceProfileRepository.observeProfiles()
        .map { profiles -> profiles.any { it.isEnabled } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    init {
        scope.launch {
            while (isActive) {
                delay(1_000L)
                tick()
            }
        }
    }

    private fun tick() {
        if (_isLocked.value) return
        if (!hasEnabledProfile.value) return
        if (!preferenceHelper.isUserLoggedIn()) return

        val timeoutMs = preferenceHelper.getFaceLockTimeoutMinutes() * 60_000L
        if (System.currentTimeMillis() - lastActivityAt.get() >= timeoutMs) {
            _isLocked.value = true
        }
    }

    /**
     * Resets the idle clock. Call on every user touch, app-wide.
     *
     * Example Usage:
     * override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
     *     sessionLockController.onUserActivity()
     *     return super.dispatchTouchEvent(ev)
     * }
     */
    fun onUserActivity() {
        lastActivityAt.set(System.currentTimeMillis())
    }

    /**
     * Manually triggers the lock overlay, e.g. from a Settings "Lock Now" row.
     *
     * @return true if the lock engaged, false if there's no enabled face profile to verify against.
     */
    fun lockNow(): Boolean {
        if (!hasEnabledProfile.value) return false
        _isLocked.value = true
        return true
    }

    /** Clears the lock after a successful verify and resets the idle clock. */
    fun unlock() {
        _isLocked.value = false
        lastActivityAt.set(System.currentTimeMillis())
    }
}
