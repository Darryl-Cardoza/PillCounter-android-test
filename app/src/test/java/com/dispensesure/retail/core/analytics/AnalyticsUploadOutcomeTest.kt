package com.dispensesure.retail.core.analytics

import com.dispensesure.retail.core.analytics.upload.AnalyticsDailyReply
import com.dispensesure.retail.core.analytics.upload.AnalyticsUploadOutcome
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

class AnalyticsUploadOutcomeTest {

    @Test fun `200 maps to UPLOADED`() =
        assertEquals(AnalyticsUploadOutcome.UPLOADED, AnalyticsUploadOutcome.of(Response.success(200, AnalyticsDailyReply())))

    @Test fun `201 maps to UPLOADED`() =
        assertEquals(AnalyticsUploadOutcome.UPLOADED, AnalyticsUploadOutcome.of(Response.success(201, AnalyticsDailyReply())))

    @Test fun `401 maps to SKIP_AUTH`() =
        assertEquals(AnalyticsUploadOutcome.SKIP_AUTH, AnalyticsUploadOutcome.of(Response.error(401, "".toResponseBody())))

    @Test fun `403 maps to POISON`() =
        assertEquals(AnalyticsUploadOutcome.POISON, AnalyticsUploadOutcome.of(Response.error(403, "".toResponseBody())))

    @Test fun `404 maps to POISON`() =
        assertEquals(AnalyticsUploadOutcome.POISON, AnalyticsUploadOutcome.of(Response.error(404, "".toResponseBody())))

    @Test fun `409 maps to POISON`() =
        assertEquals(AnalyticsUploadOutcome.POISON, AnalyticsUploadOutcome.of(Response.error(409, "".toResponseBody())))

    @Test fun `422 maps to POISON`() =
        assertEquals(AnalyticsUploadOutcome.POISON, AnalyticsUploadOutcome.of(Response.error(422, "".toResponseBody())))

    @Test fun `500 maps to RETRY_LATER`() =
        assertEquals(AnalyticsUploadOutcome.RETRY_LATER, AnalyticsUploadOutcome.of(Response.error(500, "".toResponseBody())))

    @Test fun `503 maps to RETRY_LATER`() =
        assertEquals(AnalyticsUploadOutcome.RETRY_LATER, AnalyticsUploadOutcome.of(Response.error(503, "".toResponseBody())))
}
