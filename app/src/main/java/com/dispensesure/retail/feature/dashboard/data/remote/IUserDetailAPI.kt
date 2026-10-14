package com.dispensesure.retail.feature.dashboard.data.remote

import com.dispensesure.retail.core.models.ApiResponse
import com.dispensesure.retail.core.refreshToken.domain.model.UserDetailRequest
import com.dispensesure.retail.core.utils.constants.URLConstant
import com.dispensesure.retail.feature.dashboard.domain.model.UserDetail
import com.dispensesure.retail.feature.dashboard.domain.model.UserDetailResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Interface defining authentication-related repository functions.
 */
interface IUserDetailAPI {

    /**
     * Gets authenticated user's details.
     *
     * @param authorization Bearer token header value.
     * @return Retrofit [Response] wrapping [UserDetailResponse].
     */
    @POST(URLConstant.GET_ABOUT_ME)
    suspend fun getUserDetail(
        @Header("Authorization") authorization: String,
        @Body request: UserDetailRequest
    ): Response<ApiResponse<UserDetail>>

}
