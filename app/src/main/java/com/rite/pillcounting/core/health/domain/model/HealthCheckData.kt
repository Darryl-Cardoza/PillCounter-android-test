package com.rite.pillcounting.core.health.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Root payload of a `GET /health` response body's `data` field.
 *
 * Description:
 * Represents the server's snapshot of overall service health. Only `isHealthy`
 * and `checkedAt` are load-bearing for the offline-session logic; individual
 * subsystem checks are surfaced for diagnostics.
 *
 * @property isHealthy Overall aggregate — true only when every sub-check passes.
 * @property checks Per-subsystem health details (database, etc.).
 * @property checkedAt ISO-8601 timestamp anchor supplied by the server; the client uses this
 *                    as the source of truth for offline-timer reset.
 */
@JsonClass(generateAdapter = true)
data class HealthCheckData(
    @Json(name = "is_healthy") val isHealthy: Boolean,
    @Json(name = "checks") val checks: HealthCheckDetails?,
    @Json(name = "checked_at") val checkedAt: String
)

/**
 * Container for per-subsystem health check results returned by `/health`.
 *
 * @property database Database subsystem health status (latency and detail if any).
 */
@JsonClass(generateAdapter = true)
data class HealthCheckDetails(
    @Json(name = "database") val database: HealthCheckStatus?
)

/**
 * Per-subsystem health metadata.
 *
 * @property isHealthy Whether the subsystem is currently healthy.
 * @property latencyMs Round-trip latency (ms) recorded by the backend probe.
 * @property detail Optional diagnostic message when a subsystem is unhealthy.
 */
@JsonClass(generateAdapter = true)
data class HealthCheckStatus(
    @Json(name = "is_healthy") val isHealthy: Boolean,
    @Json(name = "latency_ms") val latencyMs: Double?,
    @Json(name = "detail") val detail: String?
)