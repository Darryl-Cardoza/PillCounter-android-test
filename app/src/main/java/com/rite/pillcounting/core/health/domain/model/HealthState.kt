package com.rite.pillcounting.core.health.domain.model

/**
 * Global session-health state exposed by `SessionHealthController`.
 *
 * Description:
 * Every authenticated network request and every UI banner reacts to the
 * current value of this enum.
 *
 * States:
 * - [UNKNOWN]: pre-first-check state. Requests are allowed through so cold-start settings + `/health` can run.
 * - [HEALTHY]: `/health` succeeded recently; APIs run as normal.
 * - [OFFLINE]: `/health` failed OR connectivity dropped; every authed API is blocked at
 *   `HealthGateInterceptor`, the offline banner is shown, and the offline-threshold timer runs.
 * - [EXPIRED]: the offline-threshold has elapsed since the last successful `/health`; MainActivity
 *   observer forces logout and navigates to the auth graph.
 */
enum class HealthState {
    UNKNOWN,
    HEALTHY,
    OFFLINE,
    EXPIRED
}