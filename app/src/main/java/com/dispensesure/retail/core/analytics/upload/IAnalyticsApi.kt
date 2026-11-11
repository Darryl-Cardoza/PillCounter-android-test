package com.dispensesure.retail.core.analytics.upload

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface IAnalyticsApi {

    @POST("mobile/analytics/daily")
    suspend fun uploadDaily(
        @Header("Authorization") bearer: String,
        @Body body: AnalyticsDailyRequest,
    ): Response<AnalyticsDailyReply>
}
