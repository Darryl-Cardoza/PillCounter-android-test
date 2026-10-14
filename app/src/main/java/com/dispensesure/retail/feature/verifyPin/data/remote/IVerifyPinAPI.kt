package com.dispensesure.retail.feature.verifyPin.data.remote

import com.dispensesure.retail.core.utils.constants.URLConstant.VERIFY_OTP
import com.dispensesure.retail.feature.verifyPin.domain.model.VerifyPinRequest
import com.dispensesure.retail.feature.verifyPin.domain.model.VerifyPinResponse
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Defines the network endpoints for user authentication using Retrofit.
 */
interface IVerifyPinAPI {
    /**
     * Sends user details to the remote server to create a new account.
     * @param request A data object containing the user's email and password.
     * @return A [VerifyPinRequest] containing a success message.
     */
    @POST(VERIFY_OTP)
    suspend fun verifyPin(@Body request: VerifyPinRequest): VerifyPinResponse
}
