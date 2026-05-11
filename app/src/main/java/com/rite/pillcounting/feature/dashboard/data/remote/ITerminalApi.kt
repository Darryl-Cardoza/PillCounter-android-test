package com.rite.pillcounting.feature.dashboard.data.remote

import com.rite.pillcounting.core.utils.constants.URLConstant
import com.rite.pillcounting.feature.dashboard.domain.model.TerminalUpdateRequest
import com.rite.pillcounting.feature.dashboard.domain.model.TerminalUpdateResponse
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.PUT
import retrofit2.http.Path

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
}

