package com.dispensesure.retail.core.analytics.collect

import com.dispensesure.retail.core.analytics.model.AnalyticsDuration
import com.dispensesure.retail.core.analytics.model.AnalyticsField
import com.dispensesure.retail.core.analytics.model.AnalyticsIncrement
import com.dispensesure.retail.core.analytics.model.AnalyticsSetInsertion
import com.dispensesure.retail.core.analytics.model.AnalyticsWorkType
import com.dispensesure.retail.core.analytics.store.AnalyticsClock
import com.dispensesure.retail.core.analytics.store.AnalyticsCountedDao
import com.dispensesure.retail.core.analytics.store.AnalyticsCountedEntity
import com.dispensesure.retail.core.analytics.store.AnalyticsDateBoundary
import com.dispensesure.retail.core.events.DomainEvent
import com.dispensesure.retail.core.events.DomainEventBus
import com.dispensesure.retail.core.models.DISPENSED_QUANTITY_STEP
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.room.dao.DispenseCountDao
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.core.room.dao.DrugMasterDao
import com.dispensesure.retail.core.room.dao.StockBatchDao
import com.dispensesure.retail.core.room.dao.StockBottleInfoDao
import com.dispensesure.retail.core.room.dao.StockTxnDao
import com.dispensesure.retail.core.room.models.enums.CountStatus
import com.dispensesure.retail.core.utils.logger.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Listens to [DomainEventBus] and converts domain facts into analytics increments.
 *
 * De-duplication is done via [AnalyticsCountedDao] so it survives process restarts. Each event
 * handler is individually wrapped in try/catch — one bad event cannot kill the collector.
 * [CancellationException] is always re-thrown.
 *
 * @param applyAnalytics called with the derived increments, durations and set insertions. In
 *   production this is [com.dispensesure.retail.core.analytics.store.AnalyticsStore.apply].
 *   Injected as a lambda so the observer is testable without a real Room database. Returns false
 *   when the row could not be written, which suppresses the de-duplication marker — see
 *   [markCounted].
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
    private val durationTracker: CountDurationTracker,
    private val clock: AnalyticsClock,
    private val isHazardousDrugEnabled: () -> Boolean,
    private val applyAnalytics: suspend (
        increments: List<AnalyticsIncrement>,
        durations: List<AnalyticsDuration>,
        insertions: List<AnalyticsSetInsertion>,
    ) -> Boolean,
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
                }
            }
        }
    }

    // ── Event handlers ────────────────────────────────────────────────────────

    private suspend fun handleDispense(event: DomainEvent.DispenseCompleted) {
        if (countedDao.exists(AnalyticsCountedEntity.KIND_DISPENSE, event.txnId)) return

        val txn = dispenseTxnDao.getById(event.txnId) ?: return
        // Only fully completed counts enter analytics volume. A PARTIAL (short) count is
        // deliberately excluded, and FORCE_COMPLETED is written by the DAO sweeps rather than
        // through DispenseCountRepository.complete, so it never posts an event. iOS counts the
        // same population, so the two platforms report comparable numbers.
        if (txn.status != CountStatus.COMPLETED) return
        if (!belongsToToday(txn.updatedAt)) return

        val drug = drugDao.getDrugById(txn.drugId)

        val workType = AnalyticsWorkType.classify(
            isDispense = true,
            isHazardous = drug?.isHazardous ?: false,
            drugType = drug?.drugType,
            isPmsInitiated = txn.isComingFromHL7 == true,
        )

        val dispensedStep = DISPENSED_QUANTITY_STEP.name
        val recountStep = StepState.TARGET_REVERIFICATION.name

        val verification = dispenseCountDao.sumPillCountByStep(txn.txnId, dispensedStep)
            .takeIf { dispenseCountDao.countRowsByStep(txn.txnId, dispensedStep) > 0 }
        val untyped = dispenseCountDao.sumPillCountForUntypedRows(txn.txnId)
            .takeIf { it > 0 }

        val finalPillCount = AnalyticsFieldMapping.finalPillCount(verification, untyped)
        val recountAttempts = dispenseCountDao.countRowsByStep(txn.txnId, recountStep)
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
        val durations = buildList {
            if (durationSeconds != null) {
                add(AnalyticsDuration(workType, durationSeconds))
                if (isHazardous) add(AnalyticsDuration(AnalyticsWorkType.DISPENSE_HAZARDOUS, durationSeconds))
            }
        }

        if (applyAnalytics(increments, durations, emptyList())) {
            markCounted(AnalyticsCountedEntity.KIND_DISPENSE, event.txnId)
        }
    }

    /**
     * Counts one inventory transaction, re-read in full on every bottle line recorded against it.
     *
     * Android creates the `StockTxnEntity` already COMPLETED in the same operation as its first
     * bottle line, so there is no "transaction completed" moment to hang this on. A stock
     * transaction is one *drug in a batch*, and it keeps growing afterwards: a second lot of that
     * drug, a later loose count, or a `+/-` edit that rewrites an existing line's `bottleQty`.
     *
     * So this reads the transaction's current totals each time and applies only the difference
     * against what [AnalyticsCountedEntity] says was already counted. The transaction-count
     * increment and the duration sample are emitted once, on the first event; the bottle and
     * loose-line figures keep catching up. A replayed event yields a delta of zero and changes
     * nothing.
     */
    private suspend fun handleStock(event: DomainEvent.StockBottleRecorded) {
        val ledger = countedDao.find(AnalyticsCountedEntity.KIND_STOCK_TXN, event.stockTxnId)
        val isFirstEvent = ledger == null

        val stockTxn = stockTxnDao.getById(event.stockTxnId) ?: return
        val batch = stockTxn.batchId?.let { stockBatchDao.getById(it) }
        val drug = stockTxn.drugId?.let { drugDao.getDrugById(it) }

        val workType = AnalyticsWorkType.classify(
            isDispense = false,
            isHazardous = false,
            drugType = null,
            isPmsInitiated = batch?.requestIdFromPMS != null,
        )

        val sealedQty = stockBottleInfoDao.sumSealedBottleQty(event.stockTxnId)
        val looseLines = stockBottleInfoDao.countLooseLines(event.stockTxnId)

        val input = StockAnalyticsInput(
            workType = workType,
            sealedBottleQty = sealedQty - (ledger?.countedSealedQty ?: 0L),
            looseLineCount = looseLines - (ledger?.countedLooseLines ?: 0L),
            ndc = drug?.ndc,
            countTransaction = isFirstEvent,
        )

        val increments = AnalyticsFieldMapping.stockIncrements(input)
        val insertions = AnalyticsFieldMapping.stockInsertions(input)
        // The stopwatch runs per batch, so the first transaction to complete in it claims the
        // session's elapsed time and the rest are untimed. Timing each transaction separately
        // would measure the few milliseconds between its row being created and its first line.
        val duration = stockTxn.batchId
            ?.let { durationTracker.finish(CountDurationTracker.batchKey(it)) }
            ?.let { AnalyticsDuration(workType, it) }

        // Nothing moved and the transaction is already on the row — don't rewrite the ledger.
        if (increments.isEmpty() && insertions.isEmpty() && duration == null) return

        if (applyAnalytics(increments, listOfNotNull(duration), insertions)) {
            countedDao.upsert(
                AnalyticsCountedEntity(
                    kind = AnalyticsCountedEntity.KIND_STOCK_TXN,
                    entityId = event.stockTxnId,
                    businessDate = ledger?.businessDate
                        ?: dateBoundary.businessDate(clock.nowMillis()),
                    countedSealedQty = sealedQty,
                    countedLooseLines = looseLines,
                )
            )
        }
    }

    private suspend fun handleCountBlocked(event: DomainEvent.CountBlocked) {
        val field = when (event.reason) {
            DomainEvent.BlockReason.WRONG_DRUG -> AnalyticsField.SAFETY_WRONG_DRUG_BLOCKED
        }
        applyAnalytics(listOf(AnalyticsIncrement(field, 1L)), emptyList(), emptyList())
    }

    private suspend fun handleRxLabel() {
        applyAnalytics(
            listOf(AnalyticsIncrement(AnalyticsField.ORIGIN_RX_LABEL_PROCEEDED, 1L)),
            emptyList(),
            emptyList(),
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * True when [updatedAtMillis] falls on the business date the row would be written to.
     *
     * An HL7 resend sweep can re-save a transaction completed on an earlier day. Without this
     * guard that completion lands in today's row, and the day it belongs to may already have been
     * uploaded and deleted — misdated and unfixable.
     */
    private fun belongsToToday(updatedAtMillis: Long): Boolean {
        val now = clock.nowMillis()
        val same = dateBoundary.businessDate(updatedAtMillis) == dateBoundary.businessDate(now)
        if (!same) logger.w("Analytics skipped event from an earlier business date")
        return same
    }

    /**
     * Records the de-duplication marker, after the work it guards has been applied.
     *
     * Order matters: the marker survives restarts, so one written for work that was never applied
     * excludes that transaction permanently.
     */
    private suspend fun markCounted(kind: String, entityId: Long) {
        countedDao.insertIgnore(
            AnalyticsCountedEntity(
                kind = kind,
                entityId = entityId,
                businessDate = dateBoundary.businessDate(clock.nowMillis()),
            )
        )
    }

    private suspend fun pruneIfDateRolled() {
        try {
            countedDao.pruneOlderThan(dateBoundary.businessDate(clock.nowMillis()))
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
