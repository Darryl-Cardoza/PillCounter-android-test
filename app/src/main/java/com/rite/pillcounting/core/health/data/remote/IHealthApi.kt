package com.rite.pillcounting.core.health.data.remote

import com.rite.pillcounting.core.health.data.remote.dto.HealthCheckData
import com.rite.pillcounting.core.models.ApiResponse
import com.rite.pillcounting.core.utils.constants.URLConstant
import retrofit2.Response
import retrofit2.http.GET

/**
 * Retrofit interface for the backend health-check endpoint.
 *
 * Description:
 * Backed by an isolated OkHttp stack that sends only the `X-Server-Key`
 * header — no `Authorization`, no gate interceptor, no authenticator — so it
 * can be called safely even while the app-wide offline flag is on.
 */
interface IHealthApi {

    /**
     * Fetches current backend health status.
     *
     * @return Retrofit `Response` wrapping an [ApiResponse] whose `data` field is [HealthCheckData].
     *         Callers inspect both the HTTP code and the parsed body's `is_healthy` flag.
     */
    @GET(URLConstant.HEALTH)
    suspend fun getHealth(): Response<ApiResponse<HealthCheckData>>
}