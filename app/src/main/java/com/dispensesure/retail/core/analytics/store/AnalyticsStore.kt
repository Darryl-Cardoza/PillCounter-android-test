package com.dispensesure.retail.core.analytics.store

import com.dispensesure.retail.BuildConfig
import com.dispensesure.retail.core.analytics.model.AnalyticsDuration
import com.dispensesure.retail.core.analytics.model.AnalyticsField
import com.dispensesure.retail.core.analytics.model.AnalyticsIncrement
import com.dispensesure.retail.core.analytics.model.AnalyticsWorkType
import com.dispensesure.retail.core.utils.logger.AppLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The only writer of [DailyAnalyticsEntity].
 *
 * Serialised on a single-threaded dispatcher so no row is updated by two coroutines concurrently.
 * Every public method catches all exceptions (re-throwing [CancellationException]) so analytics
 * never breaks the calling flow.
 */
class AnalyticsStore(
    private val dao: DailyAnalyticsDao,
    private val countedDao: AnalyticsCountedDao,
    private val clock: AnalyticsClock = SystemAnalyticsClock,
    // Debug-only: when set, overrides clock.nowMillis() for upload eligibility checks.
    @Volatile private var debugClockOverrideMs: Long? = null,
    private val pharmacyUserId: () -> String,
    private val terminalName: () -> String,
    private val operatorId: () -> String,
    private val region: () -> String,
    private val appVersion: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {

    private val logger = AppLogger.create<AnalyticsStore>()
    private val dateBoundary = AnalyticsDateBoundary()

    private fun nowMillis(): Long = debugClockOverrideMs ?: clock.nowMillis()

    suspend fun apply(
        increments: List<AnalyticsIncrement>,
        durations: List<AnalyticsDuration>,
        insertions: List<Nothing> = emptyList(),
    ) {
        if (increments.isEmpty() && durations.isEmpty()) return
        runSafe("apply") {
            withContext(dispatcher) {
                val row = fetchOrCreate()
                var updated = row
                for (inc in increments) updated = updated.applyIncrement(inc)
                for (dur in durations) updated = updated.applyDuration(dur)
                dao.update(updated)
            }
        }
    }

    suspend fun rowsReadyForUpload(limit: Int = 50): List<DailyAnalyticsEntity> =
        runSafeResult("rowsReadyForUpload", emptyList()) {
            withContext(dispatcher) {
                val now = nowMillis()
                val today = dateBoundary.businessDate(now)
                dao.rowsReadyForUpload(today, now, limit = limit)
            }
        }

    suspend fun markUploaded(ids: List<Long>) {
        if (ids.isEmpty()) return
        runSafe("markUploaded") {
            withContext(dispatcher) { dao.deleteByIds(ids) }
        }
    }

    suspend fun markFailed(ids: List<Long>) {
        if (ids.isEmpty()) return
        runSafe("markFailed") {
            withContext(dispatcher) {
                val now = nowMillis()
                for (id in ids) {
                    val row = dao.findById(id) ?: continue
                    val newAttempts = row.uploadAttempts + 1
                    val backoffMs = backoffSeconds(newAttempts) * 1000L
                    dao.update(row.copy(uploadAttempts = newAttempts, nextAttemptAt = now + backoffMs))
                }
            }
        }
    }

    suspend fun markPoison(ids: List<Long>, message: String? = null) {
        if (ids.isEmpty()) return
        runSafe("markPoison") {
            withContext(dispatcher) {
                if (message != null) logger.w("Analytics row poisoned: $message")
                for (id in ids) {
                    val row = dao.findById(id) ?: continue
                    dao.update(row.copy(uploadAttempts = MAX_ATTEMPTS))
                }
            }
        }
    }

    suspend fun pruneIfNeeded() {
        runSafe("pruneIfNeeded") {
            withContext(dispatcher) {
                val today = dateBoundary.businessDate(nowMillis())
                val cutoff = cutoffDate(today)
                dao.pruneOlderThan(cutoff)
                countedDao.pruneOlderThan(today)
            }
        }
    }

    // ── Debug helpers (no-op in release) ────────────────────────────────────────

    /**
     * Shifts the store's clock 25 hours into the future and clears backoff on all pending rows
     * so they are immediately eligible for upload. Use via [AnalyticsService.debugForceUploadNow].
     *
     * The override resets automatically after the next successful drain (it is cleared in
     * [debugResetClock]) so normal operation resumes without a restart.
     */
    suspend fun debugShiftClockAndClearBackoff() {
        if (!BuildConfig.DEBUG) return
        runSafe("debugShiftClockAndClearBackoff") {
            withContext(dispatcher) {
                // Shift 25 hours: today's row becomes "yesterday" for the upload query.
                debugClockOverrideMs = clock.nowMillis() + 25L * 3_600_000L
                // Zero out nextAttemptAt on all rows so backoff doesn't block them.
                dao.debugClearAllBackoff()
                logger.w("[DEBUG] Analytics clock shifted +25 h; all backoff cleared. Call debugForceUploadNow to drain.")
            }
        }
    }

    /** Resets the debug clock override so the store returns to real time. */
    fun debugResetClock() {
        if (!BuildConfig.DEBUG) return
        debugClockOverrideMs = null
    }

    // ── Private helpers ──────────────────────────────────────────────────────────

    private suspend fun fetchOrCreate(): DailyAnalyticsEntity {
        val now = nowMillis()
        val date = dateBoundary.businessDate(now)
        val uid = pharmacyUserId()
        val term = terminalName()
        val op = operatorId()
        val rgn = region()

        val seed = DailyAnalyticsEntity(
            pharmacyUserId = uid,
            terminalName = term,
            operatorId = op,
            businessDate = date,
            region = rgn,
            appVersion = appVersion,
            timezoneOffsetMin = dateBoundary.timezoneOffsetMinutes(now),
        )
        dao.insertIgnore(seed)
        return dao.findByKey(uid, term, op, date, rgn) ?: seed
    }

    private fun DailyAnalyticsEntity.applyIncrement(inc: AnalyticsIncrement): DailyAnalyticsEntity =
        when (inc.field) {
            AnalyticsField.DISPENSE_NORMAL_COUNT -> copy(dispenseNormalCount = dispenseNormalCount + inc.delta)
            AnalyticsField.DISPENSE_NORMAL_DURATION_SAMPLES -> copy(dispenseNormalDurationSamples = dispenseNormalDurationSamples + inc.delta)
            AnalyticsField.DISPENSE_NORMAL_DURATION_SUM -> copy(dispenseNormalDurationSum = dispenseNormalDurationSum + inc.delta)
            AnalyticsField.DISPENSE_CONTROLLED_COUNT -> copy(dispenseControlledCount = dispenseControlledCount + inc.delta)
            AnalyticsField.DISPENSE_CONTROLLED_DURATION_SAMPLES -> copy(dispenseControlledDurationSamples = dispenseControlledDurationSamples + inc.delta)
            AnalyticsField.DISPENSE_CONTROLLED_DURATION_SUM -> copy(dispenseControlledDurationSum = dispenseControlledDurationSum + inc.delta)
            AnalyticsField.DISPENSE_HAZARDOUS_COUNT -> copy(dispenseHazardousCount = dispenseHazardousCount + inc.delta)
            AnalyticsField.DISPENSE_HAZARDOUS_DURATION_SAMPLES -> copy(dispenseHazardousDurationSamples = dispenseHazardousDurationSamples + inc.delta)
            AnalyticsField.DISPENSE_HAZARDOUS_DURATION_SUM -> copy(dispenseHazardousDurationSum = dispenseHazardousDurationSum + inc.delta)
            AnalyticsField.INVENTORY_CYCLE_COUNT -> copy(inventoryCycleCount = inventoryCycleCount + inc.delta)
            AnalyticsField.INVENTORY_CYCLE_DURATION_SAMPLES -> copy(inventoryCycleDurationSamples = inventoryCycleDurationSamples + inc.delta)
            AnalyticsField.INVENTORY_CYCLE_DURATION_SUM -> copy(inventoryCycleDurationSum = inventoryCycleDurationSum + inc.delta)
            AnalyticsField.INVENTORY_CYCLE_SEALED_BOTTLES_SCANNED -> copy(inventoryCycleSealedBottlesScanned = inventoryCycleSealedBottlesScanned + inc.delta)
            AnalyticsField.INVENTORY_CYCLE_NDC_COUNT -> copy(inventoryCycleNdcCount = inventoryCycleNdcCount + inc.delta)
            AnalyticsField.INVENTORY_STOCK_COUNT -> copy(inventoryStockCount = inventoryStockCount + inc.delta)
            AnalyticsField.INVENTORY_STOCK_DURATION_SAMPLES -> copy(inventoryStockDurationSamples = inventoryStockDurationSamples + inc.delta)
            AnalyticsField.INVENTORY_STOCK_DURATION_SUM -> copy(inventoryStockDurationSum = inventoryStockDurationSum + inc.delta)
            AnalyticsField.INVENTORY_STOCK_SEALED_BOTTLES_SCANNED -> copy(inventoryStockSealedBottlesScanned = inventoryStockSealedBottlesScanned + inc.delta)
            AnalyticsField.INVENTORY_STOCK_NDC_COUNT -> copy(inventoryStockNdcCount = inventoryStockNdcCount + inc.delta)
            AnalyticsField.ORIGIN_MANUAL_TRANSACTIONS -> copy(originManualTransactions = originManualTransactions + inc.delta)
            AnalyticsField.ORIGIN_RX_LABEL_PROCEEDED -> copy(originRxLabelProceeded = originRxLabelProceeded + inc.delta)
            AnalyticsField.ORIGIN_OPEN_PILL -> copy(originOpenPill = originOpenPill + inc.delta)
            AnalyticsField.SAFETY_DRUG_CODE_VERIFIED -> copy(safetyDrugCodeVerified = safetyDrugCodeVerified + inc.delta)
            AnalyticsField.SAFETY_WRONG_DRUG_BLOCKED -> copy(safetyWrongDrugBlocked = safetyWrongDrugBlocked + inc.delta)
            AnalyticsField.SAFETY_SUBSTITUTE_FLAGGED -> copy(safetySubstituteFlagged = safetySubstituteFlagged + inc.delta)
            AnalyticsField.SAFETY_EXPIRED_BLOCKED -> copy(safetyExpiredBlocked = safetyExpiredBlocked + inc.delta)
            AnalyticsField.EXPIRY_EXPIRED_SHOWN -> copy(expiryExpiredShown = expiryExpiredShown + inc.delta)
            AnalyticsField.EXPIRY_BEFORE_DOSAGE_END -> copy(expiryBeforeDosageEnd = expiryBeforeDosageEnd + inc.delta)
            AnalyticsField.EXPIRY_NEAR_SHOWN -> copy(expiryNearShown = expiryNearShown + inc.delta)
            AnalyticsField.COMPLIANCE_GLOVES_DETECTED -> copy(complianceGlovesDetected = complianceGlovesDetected + inc.delta)
            AnalyticsField.COMPLIANCE_GLOVES_NOT_DETECTED -> copy(complianceGlovesNotDetected = complianceGlovesNotDetected + inc.delta)
            AnalyticsField.COMPLIANCE_HAZARDOUS_TRAY_DETECTED -> copy(complianceHazardousTrayDetected = complianceHazardousTrayDetected + inc.delta)
            AnalyticsField.ACCURACY_COUNT_MISMATCH -> copy(accuracyCountMismatch = accuracyCountMismatch + inc.delta)
            AnalyticsField.ACCURACY_VARIANCE_ABS_SUM -> copy(accuracyVarianceAbsSum = accuracyVarianceAbsSum + inc.delta)
            AnalyticsField.ACCURACY_BACKCOUNT_PERFORMED -> copy(accuracyBackcountPerformed = accuracyBackcountPerformed + inc.delta)
            AnalyticsField.ACCURACY_BACKCOUNT_MISMATCH -> copy(accuracyBackcountMismatch = accuracyBackcountMismatch + inc.delta)
            AnalyticsField.ACCURACY_RECOUNT_ATTEMPTS_SUM -> copy(accuracyRecountAttemptsSum = accuracyRecountAttemptsSum + inc.delta)
            AnalyticsField.ACCURACY_MANUAL_ENTRY -> copy(accuracyManualEntry = accuracyManualEntry + inc.delta)
        }

    private fun DailyAnalyticsEntity.applyDuration(d: AnalyticsDuration): DailyAnalyticsEntity =
        when (d.workType) {
            AnalyticsWorkType.DISPENSE_NORMAL -> copy(
                dispenseNormalDurationSamples = dispenseNormalDurationSamples + 1,
                dispenseNormalDurationSum = dispenseNormalDurationSum + d.seconds,
                dispenseNormalDurationMin = if (dispenseNormalDurationMin < 0) d.seconds else minOf(dispenseNormalDurationMin, d.seconds),
                dispenseNormalDurationMax = maxOf(dispenseNormalDurationMax, d.seconds),
            )
            AnalyticsWorkType.DISPENSE_CONTROLLED -> copy(
                dispenseControlledDurationSamples = dispenseControlledDurationSamples + 1,
                dispenseControlledDurationSum = dispenseControlledDurationSum + d.seconds,
                dispenseControlledDurationMin = if (dispenseControlledDurationMin < 0) d.seconds else minOf(dispenseControlledDurationMin, d.seconds),
                dispenseControlledDurationMax = maxOf(dispenseControlledDurationMax, d.seconds),
            )
            AnalyticsWorkType.DISPENSE_HAZARDOUS -> copy(
                dispenseHazardousDurationSamples = dispenseHazardousDurationSamples + 1,
                dispenseHazardousDurationSum = dispenseHazardousDurationSum + d.seconds,
                dispenseHazardousDurationMin = if (dispenseHazardousDurationMin < 0) d.seconds else minOf(dispenseHazardousDurationMin, d.seconds),
                dispenseHazardousDurationMax = maxOf(dispenseHazardousDurationMax, d.seconds),
            )
            AnalyticsWorkType.INVENTORY_CYCLE_COUNT -> copy(
                inventoryCycleDurationSamples = inventoryCycleDurationSamples + 1,
                inventoryCycleDurationSum = inventoryCycleDurationSum + d.seconds,
                inventoryCycleDurationMin = if (inventoryCycleDurationMin < 0) d.seconds else minOf(inventoryCycleDurationMin, d.seconds),
                inventoryCycleDurationMax = maxOf(inventoryCycleDurationMax, d.seconds),
            )
            AnalyticsWorkType.INVENTORY_STOCK_COUNT -> copy(
                inventoryStockDurationSamples = inventoryStockDurationSamples + 1,
                inventoryStockDurationSum = inventoryStockDurationSum + d.seconds,
                inventoryStockDurationMin = if (inventoryStockDurationMin < 0) d.seconds else minOf(inventoryStockDurationMin, d.seconds),
                inventoryStockDurationMax = maxOf(inventoryStockDurationMax, d.seconds),
            )
        }

    private fun backoffSeconds(attempt: Int): Long = when (attempt) {
        1 -> 30L
        2 -> 120L
        3 -> 480L
        4 -> 1800L
        else -> 3600L
    }

    private fun cutoffDate(today: String): String {
        val date = runCatching { java.time.LocalDate.parse(today) }.getOrNull() ?: return today
        return date.minusDays(180).toString()
    }

    private suspend fun runSafe(tag: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e("Analytics $tag failed: ${e.message}")
        }
    }

    private suspend fun <T> runSafeResult(tag: String, default: T, block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e("Analytics $tag failed: ${e.message}")
            default
        }

    private companion object {
        const val MAX_ATTEMPTS = 10
    }
}
