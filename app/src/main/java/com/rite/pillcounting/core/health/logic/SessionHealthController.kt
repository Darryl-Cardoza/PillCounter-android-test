package com.rite.pillcounting.core.health.logic

import com.rite.pillcounting.core.health.domain.data.IHealthRepository
import com.rite.pillcounting.core.health.domain.model.HealthState
import com.rite.pillcounting.core.utils.common.DateUtils
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the app-wide session-health state machine.
 *
 * Description:
 * Owns the [HealthState] flow that `HealthGateInterceptor`, the offline
 * banner, the expiry watcher, and every `/auth/me` call-site read from. Also
 * owns the persistence of `lastHealthAt` (server `checked_at`) + offline
 * threshold, and emits a [syncTrigger] every time the state transitions from
 * `OFFLINE` back to `HEALTHY` so pending Room-persisted work can be pushed
 * out.
 *
 * What it does:
 * - Exposes [state], [lastHealthAt], [thresholdMs] as `StateFlow`s.
 * - Runs `/health` from [checkHealth]; every successful response updates
 *   `lastHealthAt` from the server-supplied `checked_at`, or from device
 *   wall-clock if parsing fails.
 * - `classifyAndReact` is the one-stop handler repositories call after a
 *   failed authed request. It fires `/health` for `IOException` / `5xx`, and
 *   ignores `401` (which `TokenAuthenticator` owns).
 * - `evaluateExpiry` — invoked by `ExpiryWatcher` — flips `state` to
 *   `EXPIRED` once the offline duration exceeds the threshold.
 */
@Singleton
class SessionHealthController @Inject constructor(
    private val healthRepository: IHealthRepository,
    private val preferenceHelper: PreferenceHelper
) {

    private val logger = AppLogger.create<SessionHealthController>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val checkMutex = Mutex()

    /**
     * Currently in-flight `/health` request, if any. Concurrent callers await this
     * same Deferred instead of firing another network call — coalesces the burst of
     * triggers (foreground resume + settings fetch + Dashboard preflight + connectivity
     * callback) that fire simultaneously at cold start / OTP-to-Dashboard.
     */
    private var inFlight: Deferred<Boolean>? = null

    /**
     * Wall-clock ms of the most recent completed `/health` request (success OR fail).
     * A repeat call within [MIN_INTERVAL_MS] returns the cached result rather than
     * hitting the network.
     */
    private var lastCheckMs: Long = 0L
    private var lastCheckResult: Boolean = false

    companion object {
        /**
         * Minimum interval between consecutive `/health` network hits. Anything sooner
         * returns the cached outcome. Tuned to swallow the cold-start burst without
         * masking real state changes (a hard threshold expiry is measured in hours).
         */
        private const val MIN_INTERVAL_MS: Long = 5_000L

        /**
         * Maximum number of network attempts inside a single `checkHealth()` call.
         * First-hit cold-start SSL/DNS resolution can push the first attempt past the
         * OkHttp connect timeout even on healthy backends; a single follow-up attempt
         * catches that case without turning the check into a polling loop.
         */
        private const val MAX_ATTEMPTS: Int = 2

        /** Delay between retry attempts inside a single [checkHealth] call. */
        private const val RETRY_DELAY_MS: Long = 1_000L
    }

    private val _state = MutableStateFlow(HealthState.UNKNOWN)

    /** Observable session-health state. */
    val state: StateFlow<HealthState> = _state.asStateFlow()

    private val _lastHealthAt = MutableStateFlow(preferenceHelper.getLastHealthCheckedAt())

    /**
     * Observable timestamp (device wall-clock ms) of the most recent successful `/health`
     * response. `0L` until the first success.
     */
    val lastHealthAt: StateFlow<Long> = _lastHealthAt.asStateFlow()

    private val _thresholdMs = MutableStateFlow(
        preferenceHelper.getOfflineSessionThresholdSeconds() * 1_000L
    )

    /** Observable offline threshold in milliseconds (mirrors settings-supplied seconds × 1000). */
    val thresholdMs: StateFlow<Long> = _thresholdMs.asStateFlow()

    private val _syncTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Emits `Unit` every time the state transitions from `OFFLINE` to `HEALTHY`.
     * Observed by `MainActivity` to fire `Hl7Repository.resendPendingHl7Transactions()`.
     */
    val syncTrigger: SharedFlow<Unit> = _syncTrigger.asSharedFlow()

    /**
     * Fires one `/health` request and updates state accordingly.
     *
     * What it does:
     * - Calls the isolated `HealthRepository`.
     * - On success: parses `checked_at` (falls back to device ms), persists it, flips state
     *   to `HEALTHY`, and — if the previous state was `OFFLINE` — emits [syncTrigger].
     * - On failure: flips state to `OFFLINE`, preserving the previous `lastHealthAt` so the
     *   threshold timer keeps counting from the last known-good check.
     * - Concurrent callers are serialised via a mutex so an incidental double-tap or a
     *   preflight + lifecycle-resume racing pair do not fire two /health requests.
     *
     * @return true if `/health` succeeded; false otherwise.
     */
    suspend fun checkHealth(force: Boolean = false): Boolean {
        // Fast-path: reuse a fresh recent result / join an in-flight call. Guarded by
        // the mutex only for reading + installing the shared Deferred; the network call
        // itself runs outside the mutex so callers do not serialize on a slow /health.
        val shared: Deferred<Boolean> = checkMutex.withLock {
            val existing = inFlight
            if (existing != null && !existing.isCompleted) {
                logger.d("checkHealth: joining in-flight request")
                return@withLock existing
            }
            val now = System.currentTimeMillis()
            if (!force && now - lastCheckMs < MIN_INTERVAL_MS) {
                logger.d("checkHealth: throttled (last ${now - lastCheckMs} ms ago), returning cached=$lastCheckResult")
                val cached = CompletableDeferred<Boolean>()
                cached.complete(lastCheckResult)
                return@withLock cached
            }
            val deferred = scope.async { runNetworkCheck() }
            inFlight = deferred
            deferred
        }
        return try {
            shared.await()
        } finally {
            checkMutex.withLock {
                if (inFlight === shared) inFlight = null
                if (shared.isCompleted) {
                    lastCheckMs = System.currentTimeMillis()
                    lastCheckResult = runCatching { shared.getCompleted() }.getOrDefault(false)
                }
            }
        }
    }

    /**
     * Actual network call + state update path. Never invoked directly — always routed
     * through [checkHealth] so the coalescing/throttle guards apply.
     *
     * Retry policy:
     * - Up to [MAX_ATTEMPTS] attempts with [RETRY_DELAY_MS] delay between them.
     * - Only the FINAL failure flips state to OFFLINE — a mid-loop success ends the
     *   loop and applies the healthy-state updates.
     * - Kept tight (2 attempts, 1s gap) so this does not turn into a polling loop:
     *   the outer trigger fires (foreground resume / connectivity restored / banner
     *   retry) drive subsequent probes.
     */
    private suspend fun runNetworkCheck(): Boolean {
        var lastFailure: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            val outcome = healthRepository.checkHealth()
            outcome.fold(
                onSuccess = { data ->
                    val serverMs = DateUtils.parseUtcIsoToEpochMs(data.checkedAt)
                    val stampMs = serverMs ?: System.currentTimeMillis()
                    _lastHealthAt.value = stampMs
                    preferenceHelper.setLastHealthCheckedAt(stampMs)
                    val previous = _state.value
                    _state.value = HealthState.HEALTHY
                    if (previous == HealthState.OFFLINE) {
                        logger.i("Transitioning OFFLINE -> HEALTHY, firing syncTrigger")
                        _syncTrigger.tryEmit(Unit)
                    }
                    return true
                },
                onFailure = { failure ->
                    lastFailure = failure
                    logger.w("checkHealth attempt ${attempt + 1}/$MAX_ATTEMPTS failed: ${failure.message}")
                    if (attempt < MAX_ATTEMPTS - 1) {
                        delay(RETRY_DELAY_MS)
                    }
                }
            )
        }
        logger.e("checkHealth exhausted $MAX_ATTEMPTS attempts, flipping OFFLINE", lastFailure)
        if (_state.value != HealthState.EXPIRED) {
            _state.value = HealthState.OFFLINE
        }
        return false
    }

    /**
     * Post-request classifier invoked by repositories after a failed authed call.
     *
     * What it does:
     * - `IOException` / `UnknownHostException` / `SocketTimeoutException` → likely offline;
     *   confirm with a `/health` call.
     * - HTTP `5xx` → server error or proxy page; confirm with `/health` (if healthy, the
     *   caller surfaces the error normally; if not, we flip OFFLINE).
     * - HTTP `401` → owned by `TokenAuthenticator`; no state change here.
     * - Anything else → no-op.
     *
     * @param throwable The exception thrown by Retrofit or the caller's mapping, or `null`.
     * @param httpCode The HTTP status code observed, or `null` if the failure was purely a throwable.
     */
    fun classifyAndReact(throwable: Throwable?, httpCode: Int?) {
        val is5xx = httpCode != null && httpCode in 500..599
        val isNetworkException = throwable is IOException
        if (!isNetworkException && !is5xx) return
        scope.launch { checkHealth() }
    }

    /**
     * Called by `ExpiryWatcher` once per tick while state is `OFFLINE`.
     *
     * @return true if the threshold has been exceeded and state was flipped to `EXPIRED`.
     */
    fun evaluateExpiry(): Boolean {
        if (_state.value != HealthState.OFFLINE) return false
        val elapsed = System.currentTimeMillis() - _lastHealthAt.value
        if (_lastHealthAt.value <= 0L) return false
        if (elapsed <= _thresholdMs.value) return false
        logger.w("Offline threshold exceeded (elapsed=$elapsed ms, threshold=${_thresholdMs.value} ms) — EXPIRED")
        _state.value = HealthState.EXPIRED
        return true
    }

    /**
     * Persists a new offline-session threshold received from mobile settings.
     *
     * @param seconds Threshold in seconds. Non-positive values are ignored.
     */
    fun updateThreshold(seconds: Long) {
        if (seconds <= 0L) return
        preferenceHelper.setOfflineSessionThresholdSeconds(seconds)
        _thresholdMs.value = seconds * 1_000L
        logger.i("Threshold updated to $seconds s (${_thresholdMs.value} ms)")
    }

    /**
     * Fires a background `/health` on foreground / resume.
     *
     * @return the `Job` running the check, so callers can cancel it in tests if needed.
     */
    fun onForegroundResume(): Job = scope.launch { checkHealth() }

    /**
     * Fires a background `/health` when Android reports network connectivity restored.
     *
     * @return the `Job` running the check.
     */
    fun onConnectivityRestored(): Job = scope.launch { checkHealth() }

    /**
     * Resets `state` to `UNKNOWN` after an expired-session teardown so the next login
     * starts with a clean slate. `lastHealthAt` and threshold are deliberately preserved.
     */
    fun resetAfterExpiry() {
        _state.value = HealthState.UNKNOWN
        logger.i("State reset to UNKNOWN after expiry teardown")
    }

}