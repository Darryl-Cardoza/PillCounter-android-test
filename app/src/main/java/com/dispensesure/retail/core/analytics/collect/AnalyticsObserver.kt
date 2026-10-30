package com.dispensesure.retail.core.analytics.collect

import com.dispensesure.retail.core.analytics.model.AnalyticsDuration
import com.dispensesure.retail.core.analytics.model.AnalyticsField
import com.dispensesure.retail.core.analytics.model.AnalyticsIncrement
import com.dispensesure.retail.core.analytics.model.AnalyticsWorkType
import com.dispensesure.retail.core.analytics.store.AnalyticsCountedDao
import com.dispensesure.retail.core.analytics.store.AnalyticsCountedEntity
import com.dispensesure.retail.core.analytics.store.AnalyticsDateBoundary
import com.dispensesure.retail.core.events.DomainEvent
import com.dispensesure.retail.core.events.DomainEventBus
import com.dispensesure.retail.core.room.dao.DispenseCountDao
import com.dispensesure.retail.core.room.dao.DrugMasterDao
import com.dispensesure.retail.core.room.dao.StockBatchDao
import com.dispensesure.retail.core.room.dao.StockBottleInfoDao
import com.dispensesure.retail.core.room.dao.StockTxnDao
import com.dispensesure.retail.core.room.models.enums.CountStatus
import com.dispensesure.retail.core.utils.logger.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import com.dispensesure.retail.core.room.dao.DispenseTxnDao

/**
 * Listens to [DomainEventBus] and converts domain facts into analytics increments.
 *
 * Deduplification is done via [AnalyticsCountedDao] so it survives process restarts.
 * Each event handler is individually wrapped in try/catch — one bad event cannot kill
 * the collector. [CancellationException] is always re-thrown.
 *
 * @param applyAnalytics called with the derived increments, durations, and NDC insertions.
 *   In production this is [com.dispensesure.retail.core.analytics.store.AnalyticsStore.apply].
 *   Injected as a lambda so the observer is testable without a real Room database.
 */
class AnalyticsObserver(
    private val bus: DomainEventBus,
    private val countedDao: AnalyticsCountedDao,
    private val dispenseTxnDao: DispenseTxnDao,
    private val dispenseCountDao: DispenseCountDao,
    private val drugDao: DrugMasterDao,
    private val stockTxnDao: StockTxnDao,
    private val stockBottleInfoDao: StockBottleInfoDao,
    private val stockBatchDao: StockBatchDao,
    private val isHazardousDrugEnabled: () -> Boolean,
    private val applyAnalytics: suspend (
        increments: List<AnalyticsIncrement>,
        durations: List<AnalyticsDuration>,
        insertions: List<Nothing>,
    ) -> Unit,
    private val dateBoundary: AnalyticsDateBoundary = AnalyticsDateBoundary(),
) {

    private val logger = AppLogger.create<AnalyticsObserver>()

    fun startObserving(scope: CoroutineScope) {
        scope.launch {
            bus.events.collect { event ->
                pruneIfDateRolled()
                when (event) {
                    is DomainEvent.DispenseCompleted -> safe("DispenseCompleted") { handleDispense(event) }
                    is DomainEvent.StockBottleRecorded -> safe("StockBottleRecorded") { handleStock(event) }
                    is DomainEvent.CountBlocked -> safe("CountBlocked") { handleCountBlocked(event) }
                    is DomainEvent.RxLabelProceeded -> safe("RxLabelProceeded") { handleRxLabel() }
                    is DomainEvent.ExpiryWarningResolved -> safe("ExpiryWarningResolved") { handleExpiry(event) }
                }
            }
        }
    }

    // ── Event handlers ────────────────────────────────────────────────────────

    private suspend fun handleDispense(event: DomainEvent.DispenseCompleted) {
        if (countedDao.exists(AnalyticsCountedEntity.KIND_DISPENSE, event.txnId)) return

        val txn = dispenseTxnDao.getById(event.txnId) ?: return
        if (txn.status != CountStatus.COMPLETED && txn.status != CountStatus.FORCE_COMPLETED) return

        val drug = drugDao.getDrugById(txn.drugId)

        val workType = AnalyticsWorkType.classify(
            isDispense = true,
            isHazardous = drug?.isHazardous ?: false,
            drugType = drug?.drugType,
            isPmsInitiated = txn.isComingFromHL7 == true,
        )

        val reverification = dispenseCountDao.sumPillCountByStep(txn.txnId, "TARGET_REVERIFICATION")
            .takeIf { dispenseCountDao.countRowsByStep(txn.txnId, "TARGET_REVERIFICATION") > 0 }
        val verification = dispenseCountDao.sumPillCountByStep(txn.txnId, "TARGET_VERIFICATION")
            .takeIf { dispenseCountDao.countRowsByStep(txn.txnId, "TARGET_VERIFICATION") > 0 }
        val untyped = dispenseCountDao.sumPillCountForUntypedRows(txn.txnId)
            .takeIf { it > 0 }

        val finalPillCount = AnalyticsFieldMapping.finalPillCount(reverification, verification, untyped)
        val recountAttempts = dispenseCountDao.countRowsByStep(txn.txnId, "TARGET_REVERIFICATION")
        val manualEntries = dispenseCountDao.countManualRows(txn.txnId)

        val completedAt = txn.updatedAt
        val durationSeconds = AnalyticsFieldMapping.derivedDurationSeconds(txn.createdAt, completedAt)
        val isHazardous = drug?.isHazardous ?: false

        val input = DispenseAnalyticsInput(
            workType = workType,
            isHazardous = isHazardous,
            isComingFromHL7 = txn.isComingFromHL7 == true,
            isNdcVerified = txn.isNdcVerified == true,
            isSubstitute = txn.isSubstitute,
            isHazardousDrugEnabled = isHazardousDrugEnabled(),
            isGlovesPresent = txn.isGlovesPresent,
            hazardousTrayDetected = txn.hazardousTrayDetected,
            targetCount = txn.targetCount,
            finalPillCount = finalPillCount,
            recountAttempts = recountAttempts,
            manualEntries = manualEntries,
            backcountPerformed = recountAttempts > 0,
            backcountMismatch = finalPillCount != null && txn.targetCount != null &&
                    finalPillCount != txn.targetCount && recountAttempts > 0,
            createdAt = txn.createdAt,
            completedAt = completedAt,
        )

        val increments = AnalyticsFieldMapping.dispenseIncrements(input)

        countedDao.insertIgnore(
            AnalyticsCountedEntity(
                kind = AnalyticsCountedEntity.KIND_DISPENSE,
                entityId = event.txnId,
                businessDate = dateBoundary.businessDate(System.currentTimeMillis()),
            )
        )
        val durations = buildList {
            if (durationSeconds != null) {
                add(AnalyticsDuration(workType, durationSeconds))
                if (isHazardous) add(AnalyticsDuration(AnalyticsWorkType.DISPENSE_HAZARDOUS, durationSeconds))
            }
        }
        applyAnalytics(increments, durations, emptyList())
    }

    private suspend fun handleStock(event: DomainEvent.StockBottleRecorded) {
        val txnAlreadyCounted = countedDao.exists(AnalyticsCountedEntity.KIND_STOCK_TXN, event.stockTxnId)
        val bottleAlreadyCounted = countedDao.exists(AnalyticsCountedEntity.KIND_BOTTLE, event.bottleId)
        if (bottleAlreadyCounted) return

        val stockTxn = stockTxnDao.getById(event.stockTxnId) ?: return
        val bottle = stockBottleInfoDao.getById(event.bottleId) ?: return
        val batch = stockTxn.batchId?.let { stockBatchDao.getById(it) }
        val drug = stockTxn.drugId?.let { drugDao.getDrugById(it) }

        val workType = AnalyticsWorkType.classify(
            isDispense = false,
            isHazardous = false,
            drugType = null,
            isPmsInitiated = batch?.requestIdFromPMS != null,
        )

        val isSealed = (bottle.bottleQty ?: 0) > 0

        val input = StockAnalyticsInput(
            workType = workType,
            isFirstBottleOfTxn = !txnAlreadyCounted,
            isSealed = isSealed,
            hasNdc = drug?.ndc?.isNotBlank() == true,
        )

        val result = AnalyticsFieldMapping.stockIncrements(input)

        val duration = if (!txnAlreadyCounted) {
            AnalyticsFieldMapping.derivedDurationSeconds(stockTxn.createdAt, System.currentTimeMillis())
                ?.let { AnalyticsDuration(workType, it) }
        } else null

        val today = dateBoundary.businessDate(System.currentTimeMillis())
        if (!txnAlreadyCounted) {
            countedDao.insertIgnore(
                AnalyticsCountedEntity(AnalyticsCountedEntity.KIND_STOCK_TXN, event.stockTxnId, today)
            )
        }
        countedDao.insertIgnore(
            AnalyticsCountedEntity(AnalyticsCountedEntity.KIND_BOTTLE, event.bottleId, today)
        )

        applyAnalytics(result.increments, listOfNotNull(duration), emptyList())
    }

    private suspend fun handleCountBlocked(event: DomainEvent.CountBlocked) {
        val field = when (event.reason) {
            DomainEvent.BlockReason.WRONG_DRUG -> AnalyticsField.SAFETY_WRONG_DRUG_BLOCKED
            DomainEvent.BlockReason.EXPIRED -> AnalyticsField.SAFETY_EXPIRED_BLOCKED
        }
        applyAnalytics(listOf(AnalyticsIncrement(field, 1L)), emptyList(), emptyList())
    }

    private suspend fun handleRxLabel() {
        applyAnalytics(listOf(AnalyticsIncrement(AnalyticsField.ORIGIN_RX_LABEL_PROCEEDED, 1L)), emptyList(), emptyList())
    }

    private suspend fun handleExpiry(event: DomainEvent.ExpiryWarningResolved) {
        val field = when (event.reason) {
            DomainEvent.ExpiryReason.ALREADY_EXPIRED -> AnalyticsField.EXPIRY_EXPIRED_SHOWN
            DomainEvent.ExpiryReason.BEFORE_DOSAGE_END -> AnalyticsField.EXPIRY_BEFORE_DOSAGE_END
            DomainEvent.ExpiryReason.NEAR_EXPIRY -> AnalyticsField.EXPIRY_NEAR_SHOWN
        }
        applyAnalytics(listOf(AnalyticsIncrement(field, 1L)), emptyList(), emptyList())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun pruneIfDateRolled() {
        try {
            val today = dateBoundary.businessDate(System.currentTimeMillis())
            countedDao.pruneOlderThan(today)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e("Analytics pruneIfDateRolled failed: ${e.message}")
        }
    }

    private suspend inline fun safe(tag: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e("Analytics observer $tag failed: ${e.message}")
        }
    }
}
