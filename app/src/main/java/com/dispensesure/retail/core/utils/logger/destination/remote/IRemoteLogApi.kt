package com.dispensesure.retail.core.utils.logger.destination.remote

import com.dispensesure.retail.core.utils.constants.URLConstant
import com.dispensesure.retail.core.utils.logger.destination.dto.RemoteLogRequest
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/** Retrofit interface for the log/Datadog ingest endpoint used by [com.dispensesure.retail.core.utils.logger.destination.RemoteLogDestination]. */
interface IRemoteLogApi {

    @POST(URLConstant.MOBILE_LOGS)
    suspend fun sendLog(@Body body: RemoteLogRequest): Response<ResponseBody>
}
