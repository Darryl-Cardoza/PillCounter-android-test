package com.dispensesure.retail.core.audit.upload

import com.dispensesure.retail.core.utils.constants.URLConstant
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/** Audit upload call. The body is read by the audit code itself, so every status can be handled. */
interface IAuditLogApi {

    @POST(URLConstant.AUDIT_LOGS)
    suspend fun uploadSegment(
        @Header("Authorization") authorization: String,
        @Body form: MultipartBody
    ): Response<ResponseBody>
}
