package com.rite.pillcounting.core.health.domain.data

import com.rite.pillcounting.core.health.data.remote.dto.HealthCheckData

/**
 * Domain-level contract for the health-check operation.
 *
 * Implementations wrap [com.rite.pillcounting.core.health.data.remote.IHealthApi]
 * and translate transport errors into a uniform [Result] outcome.
 */
interface IHealthRepository {

    /**
     * Calls `/health` and reports the outcome.
     *
     * Description:
     * Returns `Result.success` only when the HTTP status is 2xx AND the parsed body's
     * `is_healthy` flag is true. All other outcomes (network error, non-2xx, `is_healthy=false`)
     * collapse to `Result.failure` with a descriptive exception.
     *
     * @return [Result] wrapping [HealthCheckData] on success, or the causing exception on failure.
     *
     * Example Usage:
     * val outcome = healthRepository.checkHealth()
     * outcome.fold(
     *     onSuccess = { data -> controller.markHealthy(data.checkedAt) },
     *     onFailure = { controller.markOffline() }
     * )
     */
    suspend fun checkHealth(): Result<HealthCheckData>
}
