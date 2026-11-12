package com.dispensesure.retail.core.scanning.engine

import androidx.annotation.VisibleForTesting
import com.dispensesure.retail.core.utils.logger.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** Calls [onIdle] once after [IDLE_TIMEOUT_MS] with no counting activity. Re-arm to watch again. */
class IdleWatchdog(
    private val scope: CoroutineScope,
    private val onIdle: () -> Unit,
) {

    // Same tag as the engine, so `logcat -s PillCountingEngine` still shows the watchdog.
    private val logger = AppLogger("PillCountingEngine")
    private var idleJob: Job? = null
    // Bumped on counting activity. Atomic because the analyzer thread and Main
    // both bump it.
    private val activityTicks = AtomicLong(0)

    fun arm() {
        idleJob?.cancel()
        idleJob = scope.launch {
            val quietStepsNeeded = (IDLE_TIMEOUT_MS / IDLE_STEP_MS).toInt()
            var ticksAtLastCheck = activityTicks.get()
            var quietSteps = 0
            logger.d("$IDLE_TAG armed | timeout=${IDLE_TIMEOUT_MS}ms step=${IDLE_STEP_MS}ms ticks=$ticksAtLastCheck")
            while (true) {
                delay(IDLE_STEP_MS)
                val ticksNow = activityTicks.get()
                if (ticksNow != ticksAtLastCheck) {
                    logger.d("$IDLE_TAG activity=${ticksNow - ticksAtLastCheck} -> deadline pushed")
                    ticksAtLastCheck = ticksNow
                    quietSteps = 0
                    continue
                }
                quietSteps++
                if (quietSteps >= quietStepsNeeded) {
                    logger.d("$IDLE_TAG quiet ${quietSteps * IDLE_STEP_MS}ms -> pausing")
                    onIdle()
                    return@launch
                }
            }
        }
    }

    /** Mark counting activity so the watchdog holds off. */
    fun noteActivity() {
        activityTicks.incrementAndGet()
    }

    fun stop() {
        idleJob?.cancel()
        idleJob = null
        logger.d("$IDLE_TAG cancelled")
    }

    companion object {
        // Log prefix for the idle watchdog. Filter: logcat -s PillCountingEngine.
        internal const val IDLE_TAG = "IDLE_WATCHDOG"

        // Quiet time before the camera pauses.
        @VisibleForTesting
        internal const val IDLE_TIMEOUT_MS = 60_000L

        // Watchdog poll step. Sets how close to the timeout the pause lands.
        @VisibleForTesting
        internal const val IDLE_STEP_MS = 5_000L
    }
}
