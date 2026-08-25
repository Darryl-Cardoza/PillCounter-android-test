package com.rite.pillcounting.core.auth

/**
 * Sealed hierarchy of app-wide authentication events.
 *
 * Description:
 * Emitted on [AuthEventBus] by any component that observes an unrecoverable
 * authentication outcome (currently only "refresh returned 401"). Consumers
 * — chiefly `MainActivity` — react by clearing tokens and navigating to the
 * Login screen.
 */
sealed class AuthEvent {

    /**
     * The refresh-token endpoint itself returned HTTP 401.
     *
     * Description:
     * This is the ONLY condition that declares a session expired at runtime.
     * A normal 401 from any other endpoint gets one automatic refresh attempt
     * by `TokenAuthenticator` first — only the refresh's own 401 propagates
     * as this event.
     */
    object SessionExpired : AuthEvent()
}