package com.rite.pillcounting.core.health.logic

import androidx.annotation.VisibleForTesting
import com.rite.pillcounting.core.utils.logger.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Once-per-second poll that asks [SessionHealthController.evaluateExpiry] whether the offline
 * threshold has elapsed. Kept out of `MainActivity` composition so the loop:
 *  - is unit-testable in isolation,
 *  - survives an EXPIRED event so a second offline period after re-login still triggers, and
 *  - guards against duplicate `start()` calls stacking watchers when composition re-enters.
 *
 * The loop never terminates on its own; cancel the returned [Job] (or the parent scope)
 * to stop it. `start` is idempotent — a second call while a watcher is already active
 * returns the same [Job] instead of spawning a duplicate.
 */
object ExpiryWatcher {

    private val logger = AppLogger.create<ExpiryWatcher>()

    @Volatile private var currentJob: Job? = null

    /**
     * Starts the polling loop if not already running. Repeat calls while the previous job
     * is still active return that same job so stacked observers do not fire teardown twice.
     *
     * Description:
     * The coroutine ticks once per second and delegates the actual expiry decision to
     * [SessionHealthController.evaluateExpiry]. Observing that method flip state to EXPIRED
     * does NOT terminate the loop — the state observer in MainActivity handles the teardown,
     * and the loop keeps running so the next OFFLINE window (post-re-login) still expires.
     *
     * @param scope Coroutine scope to launch the loop on (usually the activity's `lifecycleScope`).
     * @param controller Controller whose [SessionHealthController.evaluateExpiry] is polled.
     * @return The [Job] running the loop.
     *
     * Example Usage:
     * val job = ExpiryWatcher.start(lifecycleScope, sessionHealthController)
     */
    fun start(scope: CoroutineScope, controller: SessionHealthController): Job {
        val existing = currentJob
        if (existing != null && existing.isActive) {
            logger.d("start() called while a watcher is already active — reusing existing job")
            return existing
        }
        val job = scope.launch {
            logger.i("ExpiryWatcher started")
            try {
                while (isActive) {
                    delay(1_000L)
                    if (controller.evaluateExpiry()) {
                        logger.i("ExpiryWatcher observed EXPIRED — teardown handled by observer, continuing")
                    }
                }
            } finally {
                currentJob = null
                logger.i("ExpiryWatcher stopped")
            }
        }
        currentJob = job
        return job
    }

    /**
     * Test-only reset so each test starts from a clean slate. Cancels any running loop.
     */
    @VisibleForTesting
    fun stopForTest() {
        currentJob?.cancel()
        currentJob = null
    }
}
