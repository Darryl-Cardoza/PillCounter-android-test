package com.rite.pillcounting.core.health.data

import com.rite.pillcounting.core.health.data.remote.IHealthApi
import com.rite.pillcounting.core.health.domain.data.IHealthRepository
import com.rite.pillcounting.core.health.data.remote.dto.HealthCheckData
import com.rite.pillcounting.core.utils.logger.AppLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concrete implementation of [IHealthRepository] backed by [IHealthApi].
 *
 * Description:
 * Runs the network call on [ioDispatcher] and normalises every failure mode
 * (network exception, non-2xx code, missing body, `is_healthy=false`) into
 * a `Result.failure` so callers have a single branching point.
 */
@Singleton
class HealthRepository @Inject constructor(
    private val api: IHealthApi,
    private val ioDispatcher: CoroutineDispatcher
) : IHealthRepository {

    private val logger = AppLogger.create<HealthRepository>()

    /**
     * Invokes the `/health` endpoint once.
     *
     * What it does:
     * - Executes the HTTP GET on the IO dispatcher.
     * - Returns `Result.success` only when the response is successful and the parsed body
     *   reports `is_healthy = true`.
     * - Returns `Result.failure` for network exceptions, non-2xx responses, empty bodies,
     *   or an `is_healthy = false` payload.
     *
     * @return [Result] wrapping [HealthCheckData] or the causing failure.
     */
    override suspend fun checkHealth(): Result<HealthCheckData> = withContext(ioDispatcher) {
        try {
            logger.i("Calling /health")
            val response = api.getHealth()
            if (!response.isSuccessful) {
                logger.w("/health returned HTTP ${response.code()}")
                return@withContext Result.failure(Exception("HTTP ${response.code()}"))
            }
            val body = response.body()?.data
                ?: return@withContext Result.failure(Exception("Empty /health body"))
            if (!body.isHealthy) {
                logger.w("/health reported is_healthy=false")
                return@withContext Result.failure(Exception("is_healthy=false"))
            }
            logger.i("/health healthy at ${body.checkedAt}")
            Result.success(body)
        } catch (e: Exception) {
            logger.e("/health call failed", e)
            Result.failure(e)
        }
    }
}
