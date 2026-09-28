package com.dispensesure.retail.core.faceAuth.logic

import android.content.SharedPreferences
import com.dispensesure.retail.core.faceAuth.data.FaceProfileRepository
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
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
 * The lock also survives process death: a cold start with an active login session
 * and an enrolled face profile begins locked. (Enrollment state is mirrored to
 * preferences so this decision is synchronous.) That initializer is not enough on
 * its own to stop a kill-and-relaunch bypass — see [onAppLaunch].
 *
 * What it does:
 * - [onUserActivity] resets the idle clock; call on every touch app-wide.
 * - [onAppLaunch] re-arms the lock on every fresh Activity launch, which is what
 *   actually covers "killed from recents" — the initializer below cannot, because
 *   a foreground service keeps the process (and this singleton) alive.
 * - [isLocked] is the single source of truth the UI observes to show/hide the overlay.
 * - [lockNow] lets Settings (and a fresh login) trigger the same overlay on demand.
 * - [startOnScan] tells the overlay to open on the verify camera instead of the lock screen.
 * - [unlock] is called after a successful face verify.
 */
@Singleton
class SessionLockController @Inject constructor(
    private val preferenceHelper: PreferenceHelper,
    faceProfileRepository: FaceProfileRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lastActivityAt = AtomicLong(System.currentTimeMillis())
    private val logger = AppLogger.create<SessionLockController>()

    // Start locked on a cold start with an active session: process death must not
    // bypass the face lock. Only covers a genuinely new process — a fresh launch
    // into a surviving process is [onAppLaunch]'s job.
    private val _isLocked = MutableStateFlow(
        preferenceHelper.isUserLoggedIn() && preferenceHelper.hasEnabledFaceProfile()
    )
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    // Whether the current lock should open straight on the verify camera. Only a
    // fresh login does; idle timeout and cold start keep the idle lock screen.
    private val _startOnScan = MutableStateFlow(false)
    val startOnScan: StateFlow<Boolean> = _startOnScan.asStateFlow()

    // Who verified into THIS session. Deliberately not derived from FaceProfileEntity.lastUsedAt:
    // that column is persistent history, so it would keep naming an operator across restarts and
    // would promote some other enrolled profile the moment the real one was switched off.
    private val _verifiedFaceProfileId = MutableStateFlow<Long?>(null)
    val verifiedFaceProfileId: StateFlow<Long?> = _verifiedFaceProfileId.asStateFlow()

    // Watches the pref, not each logout call site — several flip it directly. Held as a field:
    // the platform keeps only a weak reference to the listener.
    private val loggedOutListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == preferenceHelper.userLoggedInKey && !preferenceHelper.isUserLoggedIn()) {
                _verifiedFaceProfileId.value = null
            }
        }

    /** Whether at least one enrolled face profile currently participates in verify matching. */
    val hasEnabledProfile: StateFlow<Boolean> = faceProfileRepository.observeProfiles()
        .map { profiles -> profiles.any { it.isEnabled } }
        .catch { e -> logger.e("Observing enabled face profiles failed", e, event = LogEvent.FACE_VERIFY_FAILED) }
        .stateIn(scope, SharingStarted.Eagerly, preferenceHelper.hasEnabledFaceProfile())

    init {
        scope.launch {
            while (isActive) {
                delay(1_000L)
                tick()
            }
        }
        scope.launch {
            hasEnabledProfile.collect { hasEnabled ->
                try {
                    preferenceHelper.saveHasEnabledFaceProfile(hasEnabled)
                    // No enabled profile means nothing to verify against — release the lock.
                    if (!hasEnabled && _isLocked.value) {
                        _isLocked.value = false
                        _startOnScan.value = false
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.e("Reacting to hasEnabledProfile change failed", e, event = LogEvent.FACE_VERIFY_FAILED)
                }
            }
        }
        preferenceHelper.registerOnChangeListener(loggedOutListener)
    }

    private fun tick() {
        if (_isLocked.value) return
        if (!hasEnabledProfile.value) return
        if (!preferenceHelper.isUserLoggedIn()) return

        val timeoutMs = preferenceHelper.getFaceLockTimeoutMinutes() * 60_000L
        if (System.currentTimeMillis() - lastActivityAt.get() >= timeoutMs) {
            _isLocked.value = true
            _verifiedFaceProfileId.value = null
        }
    }

    /**
     * Records which enrolled profile verified into this session. Call on every successful
     * face match, alongside the profile's `lastUsedAt` stamp.
     *
     * @param faceProfileId The matched profile's Room id.
     */
    fun onFaceVerified(faceProfileId: Long) {
        _verifiedFaceProfileId.value = faceProfileId
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
     * Manually triggers the lock overlay, e.g. from a Settings "Lock Now" row or
     * right after a successful login.
     *
     * @param startOnScan Open the overlay on the verify camera instead of the idle lock screen.
     * @return true if the lock engaged, false if there's no enabled face profile to verify against.
     */
    fun lockNow(startOnScan: Boolean = false): Boolean {
        if (!hasEnabledProfile.value) return false
        _startOnScan.value = startOnScan
        _isLocked.value = true
        _verifiedFaceProfileId.value = null
        return true
    }

    /**
     * Re-arms the lock on a fresh Activity launch, which the initializer misses.
     * Returns true if the lock engaged.
     */
    fun onAppLaunch(): Boolean {
        // Cleared unconditionally: a foreground service keeps this singleton alive across an
        // app kill, so a relaunch would otherwise inherit the previous session's operator.
        _verifiedFaceProfileId.value = null
        if (!preferenceHelper.isUserLoggedIn()) return false
        return lockNow()
    }

    /** Clears the lock after a successful verify and resets the idle clock. */
    fun unlock() {
        _isLocked.value = false
        _startOnScan.value = false
        lastActivityAt.set(System.currentTimeMillis())
    }
}
