package com.rite.pillcounting.core.health.logic

import com.rite.pillcounting.core.utils.logger.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Tiny helper that ticks once per second and asks
 * [SessionHealthController.evaluateExpiry] whether the offline threshold has
 * elapsed. Extracted from `MainActivity` to keep the composable file lean and
 * to make the polling loop unit-testable in isolation.
 */
object ExpiryWatcher {

    private val logger = AppLogger.create<ExpiryWatcher>()

    /**
     * Starts a coroutine that polls [SessionHealthController.evaluateExpiry] once a
     * second. Cancel the returned [Job] to stop the watcher (e.g. from
     * `MainActivity.onDestroy`).
     *
     * @param scope The scope to launch the loop on (usually the activity's `lifecycleScope`).
     * @param controller The controller instance to poll.
     * @return The [Job] running the tick loop.
     *
     * Example Usage:
     * val job = ExpiryWatcher.start(lifecycleScope, sessionHealthController)
     */
    fun start(scope: CoroutineScope, controller: SessionHealthController): Job =
        scope.launch {
            logger.i("ExpiryWatcher started")
            while (isActive) {
                delay(1_000L)
                if (controller.evaluateExpiry()) {
                    // Observer in MainActivity handles the teardown; no more work here.
                    logger.i("ExpiryWatcher observed EXPIRED — stopping")
                    return@launch
                }
            }
        }
}