package com.dispensesure.retail.feature.settings.data.remote

import com.dispensesure.retail.core.models.ApiResponse
import com.dispensesure.retail.core.refreshToken.domain.model.RefreshTokenRequest
import com.dispensesure.retail.core.refreshToken.domain.model.RefreshTokenResponse
import com.dispensesure.retail.feature.settings.domain.model.SettingsDataDto
import com.dispensesure.retail.core.utils.constants.URLConstant
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Defines the network endpoints for the application using Retrofit.
 */
interface IApplicationSettingInterface {

    /**
     * Fetches the application settings from the remote server.
     * Pass Authorization header dynamically from SharedPreferences.
     */
    @GET(URLConstant.MOBILE_SETTINGS)
    suspend fun getApplicationSettings(
        @Query("platform") androidVersion: String
    ): ApiResponse<SettingsDataDto>

    /**
     * Refreshes access token using a valid refresh token.
     */
    @POST(URLConstant.REFRESH_TOKEN)
    @Headers("Content-Type: ${URLConstant.CONTENT_TYPE}")
    suspend fun refreshToken(
        @Body request: RefreshTokenRequest
    ): Response<RefreshTokenResponse>

}
