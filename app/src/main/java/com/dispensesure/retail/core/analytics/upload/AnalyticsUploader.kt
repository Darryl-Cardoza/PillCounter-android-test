package com.dispensesure.retail.core.analytics.upload

import com.dispensesure.retail.core.analytics.collect.PmsLifecycle
import com.dispensesure.retail.core.analytics.collect.PmsLifecycleQuery
import com.dispensesure.retail.core.analytics.store.AnalyticsStore
import com.dispensesure.retail.core.analytics.store.DailyAnalyticsEntity
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
        val body = AnalyticsDailyRequest(deviceKey = deviceKey, records = buildRecords(rows))

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
            AnalyticsUploadOutcome.UPLOADED -> store.markUploaded(ids)
            AnalyticsUploadOutcome.SKIP_AUTH -> logger.w("Analytics upload skipped: 401 — will retry after re-auth")
            AnalyticsUploadOutcome.RETRY_LATER -> store.markFailed(ids)
            AnalyticsUploadOutcome.POISON -> {} // handled above
        }
    }

    /**
     * Encodes [rows] into wire records, attaching the PMS lifecycle block correctly.
     *
     * Two things this gets right that a naive `rows.map` does not:
     *
     * 1. **One query per business date.** A drain takes up to 50 rows and a backlog spans several
     *    days, so the block must be queried for each row's own date. Querying once and reusing it
     *    stamps every day with one day's numbers.
     * 2. **One block per date, not per record.** The row key includes the operator, but the PMS
     *    lifecycle is terminal-wide — [PmsLifecycleQuery] filters by day, never by operator. Three
     *    operators on one day would otherwise send the same `received` three times, which a server
     *    that sums records reads as triple the orders. The real block goes on the lowest-id record
     *    for each date and the rest send zeros, so the day's totals are stated exactly once.
     *
     * The lowest-id pick is deterministic, so a retry of the same rows produces a byte-identical
     * body.
     */
    private suspend fun buildRecords(rows: List<DailyAnalyticsEntity>): List<AnalyticsDailyRecord> {
        val pmsByDate = rows.map { it.businessDate }.distinct()
            .associateWith { pmsLifecycleQuery.query(it) }
        val pmsCarrierIds = rows.groupBy { it.businessDate }
            .mapNotNull { (_, sameDate) -> sameDate.minByOrNull { it.id }?.id }
            .toSet()

        return rows.map { row ->
            val pms = if (row.id in pmsCarrierIds) {
                pmsByDate[row.businessDate] ?: PmsLifecycle.ZERO
            } else {
                PmsLifecycle.ZERO
            }
            row.toRecord(pms)
        }
    }
}
