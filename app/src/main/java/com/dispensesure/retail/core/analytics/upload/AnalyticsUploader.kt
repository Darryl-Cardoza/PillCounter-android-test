package com.dispensesure.retail.core.analytics.upload

import com.dispensesure.retail.core.analytics.collect.PmsLifecycleQuery
import com.dispensesure.retail.core.analytics.store.AnalyticsStore
import com.dispensesure.retail.core.utils.device.DeviceKeyProvider
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * Fetches rows ready for upload, POSTs them, and routes the outcome to [AnalyticsStore].
 *
 * One drain at a time — an [AtomicBoolean] guard ensures concurrent [drainIfNeeded] calls
 * collapse into a single POST round rather than producing duplicate requests.
 */
class AnalyticsUploader(
    private val store: AnalyticsStore,
    private val api: IAnalyticsApi,
    private val preferenceHelper: PreferenceHelper,
    private val deviceKeyProvider: DeviceKeyProvider,
    private val pmsLifecycleQuery: PmsLifecycleQuery,
) {

    private val logger = AppLogger.create<AnalyticsUploader>()
    private val draining = AtomicBoolean(false)

    suspend fun drainIfNeeded() {
        if (!draining.compareAndSet(false, true)) return
        try {
            drain()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e("Analytics drain failed: ${e.message}")
        } finally {
            draining.set(false)
        }
    }

    private suspend fun drain() {
        val token = preferenceHelper.getAccessToken()
        if (token.isNullOrBlank()) return

        val deviceKey = deviceKeyProvider.getDeviceKey()
        if (deviceKey.isNullOrBlank()) return

        val bearer = "Bearer $token"
        val rows = store.rowsReadyForUpload()
        if (rows.isEmpty()) return

        val ids = rows.map { it.id }
        val pms = pmsLifecycleQuery.query(rows.first().businessDate)
        val records = rows.map { it.toRecord(pms) }
        val body = AnalyticsDailyRequest(deviceKey = deviceKey, records = records)

        val outcome = try {
            val response = api.uploadDaily(bearer, body)
            val classification = AnalyticsUploadOutcome.of(response)
            if (classification == AnalyticsUploadOutcome.POISON) {
                val message = response.body()?.message
                    ?: runCatching { response.errorBody()?.string() }.getOrNull()
                store.markPoison(ids, message)
                return
            }
            classification
        } catch (e: IOException) {
            AnalyticsUploadOutcome.RETRY_LATER
        }

        when (outcome) {
            AnalyticsUploadOutcome.UPLOADED -> {
                store.markUploaded(ids)
                store.debugResetClock()
            }
            AnalyticsUploadOutcome.SKIP_AUTH -> logger.w("Analytics upload skipped: 401 — will retry after re-auth")
            AnalyticsUploadOutcome.RETRY_LATER -> store.markFailed(ids)
            AnalyticsUploadOutcome.POISON -> {} // handled above
        }
    }
}
