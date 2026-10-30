package com.dispensesure.retail.core.analytics.upload

import retrofit2.Response

enum class AnalyticsUploadOutcome {
    UPLOADED,
    SKIP_AUTH,
    POISON,
    RETRY_LATER;

    companion object {
        fun of(response: Response<AnalyticsDailyReply>): AnalyticsUploadOutcome = when {
            response.isSuccessful -> UPLOADED
            response.code() == 401 -> SKIP_AUTH
            response.code() in 400..499 -> POISON
            else -> RETRY_LATER
        }
    }
}
