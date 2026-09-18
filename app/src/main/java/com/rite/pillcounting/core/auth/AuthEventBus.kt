package com.rite.pillcounting.core.auth

import com.rite.pillcounting.core.utils.logger.AppLogger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide bus for [AuthEvent] emissions.
 *
 * Description:
 * A single `@Singleton` funnel through which any producer (the OkHttp
 * `TokenAuthenticator` or a manual-401 repository) can announce that the
 * refresh-token call itself returned 401 — the app-wide "your session is
 * really over" signal.
 *
 * What it does:
 * - Exposes [events] as a hot `SharedFlow` so `MainActivity` can observe it via
 *   `LaunchedEffect` and perform the standard teardown (clear tokens, unlock
 *   face overlay, navigate to Login, toast).
 * - Exposes [tryPublish] as a non-blocking publish path for producers that
 *   run inside a `runBlocking` scope such as `TokenAuthenticator.authenticate`.
 * - Uses a small buffer with `DROP_OLDEST` overflow so duplicate emissions
 *   from concurrent producers do not stack up — the consumer only needs a
 *   single event to nav away.
 */
@Singleton
class AuthEventBus @Inject constructor() {

    private val logger = AppLogger.create<AuthEventBus>()

    private val _events = MutableSharedFlow<AuthEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Public read-only stream of auth events. */
    val events: SharedFlow<AuthEvent> = _events.asSharedFlow()

    /**
     * Non-blocking publish for callers that cannot suspend (e.g. OkHttp
     * `Authenticator.authenticate()` running inside `runBlocking`).
     *
     * @param event The [AuthEvent] to broadcast.
     * @return true if the event was accepted into the buffer.
     */
    fun tryPublish(event: AuthEvent): Boolean {
        logger.i("tryPublish($event)")
        return _events.tryEmit(event)
    }
}
