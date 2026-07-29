package com.rite.pillcounting.feature.profile.data.remote

import com.rite.pillcounting.core.models.ApiResponse
import com.rite.pillcounting.core.utils.constants.URLConstant
import com.rite.pillcounting.feature.profile.domain.model.CountriesData
import com.rite.pillcounting.feature.profile.domain.model.ProfileDeleteResponse
import com.rite.pillcounting.feature.profile.domain.model.ProfileUpdateRequest
import com.rite.pillcounting.feature.profile.domain.model.ProfileUpdateResponse
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Retrofit service interfaceDetail for profile-related API operations.
 */
interface IProfileApi {

    /**
     * Updates the user's profile information.
     *
     * @param request Profile update request body.
     * @return [ProfileUpdateResponse] containing the updated profile info.
     */
    @POST(URLConstant.UPDATE_PROFILE)
    suspend fun updateProfile(
        @Header("Authorization") authorization: String,
        @Body request: ProfileUpdateRequest
    ): ProfileUpdateResponse

    /**
     * Deletes the user's profile permanently.
     *
     * @return [ProfileDeleteResponse] indicating success or failure.
     */
    @DELETE(URLConstant.DELETE_PROFILE)
    suspend fun deleteProfile(
        @Header("Authorization") authorization: String,
    ): ProfileDeleteResponse

    /**
     * Fetches the reference list of countries and their states/provinces.
     *
     * @return [ApiResponse] wrapping [CountriesData].
     */
    @GET(URLConstant.GET_COUNTRIES)
    suspend fun getCountries(): ApiResponse<CountriesData>
}
