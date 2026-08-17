package com.rite.pillcounting.feature.dashboard.data.remote

import com.rite.pillcounting.core.utils.constants.URLConstant
import com.rite.pillcounting.feature.dashboard.domain.model.TerminalListResponse
import com.rite.pillcounting.feature.dashboard.domain.model.TerminalUpdateRequest
import com.rite.pillcounting.feature.dashboard.domain.model.TerminalUpdateResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit service interface for terminal-related API operations.
 */
interface ITerminalApi {

    /**
     * Updates terminal settings (name and active status).
     *
     * @param authorization Bearer token header value.
     * @param terminalId The ID of the terminal to update.
     * @param request Terminal update request body.
     * @return [TerminalUpdateResponse] containing the updated terminal info.
     */
    @PUT("${URLConstant.UPDATE_TERMINAL}{terminalId}")
    suspend fun updateTerminal(
        @Header("Authorization") authorization: String,
        @Path("terminalId") terminalId: String,
        @Body request: TerminalUpdateRequest
    ): TerminalUpdateResponse

    /**
     * Lists terminals for the pharmacy.
     *
     * @param authorization Bearer token header value.
     * @param availableOnly When true, restricts to free terminals plus the one [deviceKey] already holds.
     * @param deviceKey Stable per-install device identifier (Firebase Installations ID).
     * @return [TerminalListResponse] containing the terminal list.
     */
    @GET(URLConstant.GET_TERMINALS_LIST)
    suspend fun getTerminals(
        @Header("Authorization") authorization: String,
        @Query("available_only") availableOnly: Boolean,
        @Query("device_key") deviceKey: String
    ): TerminalListResponse
}

