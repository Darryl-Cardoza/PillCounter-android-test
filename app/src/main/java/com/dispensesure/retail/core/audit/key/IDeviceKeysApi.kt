package com.dispensesure.retail.core.audit.key

import com.dispensesure.retail.core.utils.constants.URLConstant
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/** DEK registration call. The body is read by the key code itself, so every status can be handled. */
interface IDeviceKeysApi {

    @POST(URLConstant.DEVICE_KEYS)
    suspend fun registerKey(
        @Header("Authorization") authorization: String,
        @Body body: RequestBody
    ): Response<ResponseBody>
}
