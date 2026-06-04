package com.rite.pillcounting.core.scanning.data.remote

import com.rite.pillcounting.core.scanning.domain.model.DrugDataResponse
import com.rite.pillcounting.core.scanning.domain.model.GetNdcRequestModel
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Defines the API endpoints for fetching drug information from your backend.
 */
interface IDrugAPI {

    /**
     * Fetches drug information based on a National Drug Code (NDC).
     *
     * Example:
     * GET /drugs/ndc/{ndc}
     *
     * @param ndc National Drug Code.
     * @return A [DrugDataResponse] containing the search results.
     */
    @POST("drugs/ndc/new")
    suspend fun getDrugInfoByNdc(
        @Header("Authorization") authorization: String,
        @Body getNdcRequestModel: GetNdcRequestModel,
    ): DrugDataResponse
}