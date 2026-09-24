package com.dispensesure.retail.feature.dashboard.domain.data

import com.dispensesure.retail.core.models.ApiResponse
import com.dispensesure.retail.feature.dashboard.domain.model.UserDetail
import com.dispensesure.retail.feature.dashboard.domain.model.UserDetailResponse

/**
 * Contract for authentication-related data operations.
 */
interface IUserDetailRepository {


    /**
     * Fetches the details of the currently authenticated user.
     *
     * @param token The Bearer token for authorization.
     * @return Flow emitting [UserDetailResponse] on success or error.
     */
    suspend fun getUserDetail(token: String): Result<ApiResponse<UserDetail>>
}
