package com.dispensesure.retail.core.analytics.store

import com.dispensesure.retail.core.analytics.model.AnalyticsDuration
import com.dispensesure.retail.core.analytics.model.AnalyticsField
import com.dispensesure.retail.core.analytics.model.AnalyticsIncrement
import com.dispensesure.retail.core.analytics.model.AnalyticsSetInsertion
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
    private val pharmacyUserId: () -> String,
    private val terminalName: () -> String,
    private val operatorId: () -> String,
    private val region: () -> String,
    private val appVersion: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {

    private val logger = AppLogger.create<AnalyticsStore>()
    private val dateBoundary = AnalyticsDateBoundary()

    private fun nowMillis(): Long = clock.nowMillis()

    /**
     * Folds [increments], [durations] and [insertions] into today's row.
     *
     * Returns **true only when the row was written**. The caller needs that answer: the observer
     * records a de-duplication marker for the transaction it just counted, and a marker written
     * for work that was never applied excludes that transaction permanently, since the marker
     * survives restarts. Errors are still caught here rather than thrown, so analytics cannot
     * break a calling flow — the failure is reported, not propagated.
     *
     * An empty call returns true: there was nothing to write, and the caller should still mark the
     * transaction counted so it is not re-examined on every event.
     */
    suspend fun apply(
        increments: List<AnalyticsIncrement>,
        durations: List<AnalyticsDuration>,
        insertions: List<AnalyticsSetInsertion> = emptyList(),
    ): Boolean {
        if (increments.isEmpty() && durations.isEmpty() && insertions.isEmpty()) return true
        return runSafeResult("apply", default = false) {
            withContext(dispatcher) {
                val row = fetchOrCreate()
                var updated = row
                for (inc in increments) updated = updated.applyIncrement(inc)
                for (dur in durations) updated = updated.applyDuration(dur)
                for (ins in insertions) updated = updated.applyInsertion(ins)
                dao.update(updated)
                true
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
            AnalyticsField.DISPENSE_CONTROLLED_COUNT -> copy(dispenseControlledCount = dispenseControlledCount + inc.delta)
            AnalyticsField.DISPENSE_HAZARDOUS_COUNT -> copy(dispenseHazardousCount = dispenseHazardousCount + inc.delta)
            AnalyticsField.INVENTORY_CYCLE_COUNT -> copy(inventoryCycleCount = inventoryCycleCount + inc.delta)
            AnalyticsField.INVENTORY_CYCLE_SEALED_BOTTLES_SCANNED -> copy(inventoryCycleSealedBottlesScanned = inventoryCycleSealedBottlesScanned + inc.delta)
            AnalyticsField.INVENTORY_STOCK_COUNT -> copy(inventoryStockCount = inventoryStockCount + inc.delta)
            AnalyticsField.INVENTORY_STOCK_SEALED_BOTTLES_SCANNED -> copy(inventoryStockSealedBottlesScanned = inventoryStockSealedBottlesScanned + inc.delta)
            AnalyticsField.ORIGIN_MANUAL_TRANSACTIONS -> copy(originManualTransactions = originManualTransactions + inc.delta)
            AnalyticsField.ORIGIN_RX_LABEL_PROCEEDED -> copy(originRxLabelProceeded = originRxLabelProceeded + inc.delta)
            AnalyticsField.ORIGIN_OPEN_PILL -> copy(originOpenPill = originOpenPill + inc.delta)
            AnalyticsField.SAFETY_DRUG_CODE_VERIFIED -> copy(safetyDrugCodeVerified = safetyDrugCodeVerified + inc.delta)
            AnalyticsField.SAFETY_WRONG_DRUG_BLOCKED -> copy(safetyWrongDrugBlocked = safetyWrongDrugBlocked + inc.delta)
            AnalyticsField.SAFETY_SUBSTITUTE_FLAGGED -> copy(safetySubstituteFlagged = safetySubstituteFlagged + inc.delta)
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

    /**
     * Adds a drug code to one of the inventory NDC sets.
     *
     * Dispense work types have no set — `ndc_scanned` is an inventory-only wire field — so they
     * are ignored rather than silently routed to the wrong column.
     */
    private fun DailyAnalyticsEntity.applyInsertion(ins: AnalyticsSetInsertion): DailyAnalyticsEntity =
        when (ins.workType) {
            AnalyticsWorkType.INVENTORY_CYCLE_COUNT ->
                copy(inventoryCycleNdcs = AnalyticsNdcSet.insert(inventoryCycleNdcs, ins.value))
            AnalyticsWorkType.INVENTORY_STOCK_COUNT ->
                copy(inventoryStockNdcs = AnalyticsNdcSet.insert(inventoryStockNdcs, ins.value))
            AnalyticsWorkType.DISPENSE_NORMAL,
            AnalyticsWorkType.DISPENSE_CONTROLLED,
            AnalyticsWorkType.DISPENSE_HAZARDOUS -> this
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
