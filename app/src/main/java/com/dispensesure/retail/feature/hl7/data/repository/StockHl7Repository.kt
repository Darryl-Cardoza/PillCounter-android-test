package com.dispensesure.retail.feature.hl7.data.repository

import android.content.Context
import com.dispensesure.retail.R
import com.dispensesure.retail.core.faceAuth.data.OperatorNameProvider
import com.dispensesure.retail.core.room.dao.StockBatchDao
import com.dispensesure.retail.core.room.dao.StockBottleInfoDao
import com.dispensesure.retail.core.room.dao.StockTxnDao
import com.dispensesure.retail.core.room.models.StockBatchEntity
import com.dispensesure.retail.core.room.models.StockTxnEntity
import com.dispensesure.retail.core.room.models.enums.BatchStatus
import com.dispensesure.retail.core.room.models.enums.CountStatus
import com.dispensesure.retail.core.scanning.data.DrugResolver
import com.dispensesure.retail.core.scanning.domain.model.DrugLookup
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import com.dispensesure.retail.feature.hl7.core.Hl7MessageSender
import com.dispensesure.retail.feature.hl7.data.Hl7ConfigProvider
import com.dispensesure.retail.feature.hl7.notification.Hl7Notifier
import com.dispensesure.retail.feature.hl7.util.InventoryMessageBuilder
import com.dispensesure.retail.feature.hl7.util.isSuccessAck
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.rite.hl7.model.HL7Message
import org.rite.hl7.model.segment.INVSegment
import org.rite.hl7.model.segment.RXESegment
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stock HL7: turns a PMS inventory request (INR) into a batch, and sends
 * finished batches to the PMS as INU^U05, chunk by chunk.
 */
@Singleton
class StockHl7Repository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stockTxnDao: StockTxnDao,
    private val stockBottleInfoDao: StockBottleInfoDao,
    private val operatorNameProvider: OperatorNameProvider,
    private val stockBatchDao: StockBatchDao,
    private val hl7MessageSender: Hl7MessageSender,
    private val drugResolver: DrugResolver,
    private val notifier: Hl7Notifier,
    private val hl7ConfigProvider: Hl7ConfigProvider,
) {

    private val logger = AppLogger.create<StockHl7Repository>()
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Reconnect, the Unsynced screen, END COUNT and the offline drain can all start a resend;
    // without this, two passes send the same chunk twice.
    // One worker runs passes in turn; triggers during a pass collapse into one more pass.
    private val resendRequests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (resendRequest in resendRequests) sendAllPendingBatches()
        }
    }

    /**
     * Builds and sends the inventory response for a batch, splitting it into
     * multiple independently-ACKed HL7 messages (chunks) when the batch is large,
     * so no single message risks exceeding PMS's message-size ceiling.
     *
     * Chunks are sent strictly in order, one at a time, each only after the
     * previous chunk's ACK succeeds. Progress is persisted
     * ([StockBatchDao.markChunkAcked]) immediately after every successful ACK, so if
     * PMS goes offline or the app is killed mid-sync, the next call (via
     * [resendPendingBatches]) resumes at the first un-ACKed chunk
     * instead of resending chunks PMS has already applied. The batch is marked
     * synced ([StockBatchDao.markBatchSynced]) only once every chunk has ACKed.
     */
    suspend fun sendInventory(batchId: Long) {
        try {
            if (batchId in stockBatchDao.getTutorialBatchIds()) {
                logger.i("Tutorial batch — never sending to PMS, batchId=$batchId")
                return
            }
            logger.i("sendInventory started, batchId=$batchId")

            val batch = stockBatchDao.getById(batchId)
            if (batch == null) {
                logger.w("No batch found for batchId=$batchId")
                return
            }

            val bottles = stockBottleInfoDao.getByBatchId(batchId)
            logger.i("bottles count = ${bottles.size}, bottles = $bottles")

            val chunks = InventoryMessageBuilder.buildInventoryMessageChunks(
                batch = batch,
                bottles = bottles,
                config = hl7ConfigProvider.current()
            )

            // totalChunks is fixed the first time this batch starts sending; on a
            // resume, chunk contents are rebuilt from the same DB rows, so the
            // count should match what was persisted, but persist defensively in
            // case rows changed since the last attempt (e.g. an edit while offline).
            if (batch.totalChunks != chunks.size) {
                stockBatchDao.setTotalChunks(batchId, chunks.size)
            }

            val resumeFromChunk = batch.lastAckedChunkIndex + 1
            logger.i("Sending ${chunks.size} inventory chunk(s) for batchId=$batchId, resuming at chunk $resumeFromChunk")

            for (chunk in chunks) {
                if (chunk.chunkIndex < resumeFromChunk) {
                    // Already ACKed on a prior attempt — do not resend.
                    continue
                }

                logger.i("Sending inventory chunk ${chunk.chunkIndex}/${chunk.totalChunks} for batchId=$batchId")
                val result = hl7MessageSender.sendRaw(chunk.message)
                val ackAccepted = result.getOrNull()?.let { isSuccessAck(it) } ?: false

                if (result.isSuccess && ackAccepted) {
                    stockBatchDao.markChunkAcked(batchId, chunk.chunkIndex)
                    logger.i("Chunk ${chunk.chunkIndex}/${chunk.totalChunks} ACKed for batchId=$batchId")
                } else {
                    // Stop here — do not send later chunks out of order. The batch
                    // stays unsynced and will resume at this exact chunk next time.
                    val chunkMessage = "Chunk ${chunk.chunkIndex}/${chunk.totalChunks} failed/NAKed for batchId=$batchId: " +
                        "${result.exceptionOrNull()?.message ?: "non-success ACK"}"
                    // A failed send is already logged by Hl7ServiceManager; only a NAK is new here.
                    if (result.isFailure) {
                        logger.w(chunkMessage, event = LogEvent.HL7_SEND_FAILED)
                    } else {
                        logger.e(chunkMessage, event = LogEvent.HL7_SEND_FAILED)
                    }
                    return
                }
            }

            stockBatchDao.markBatchSynced(batchId)
            logger.i("All chunks sent — inventory HL7 sync complete for batchId=$batchId")

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e("sendInventory failed for batchId=$batchId", e, event = LogEvent.HL7_SEND_FAILED)
        }
    }

    fun resendPendingBatches() {
        resendRequests.trySend(Unit)
    }

    private suspend fun sendAllPendingBatches() {
        try {
            val pendingBatches = stockBatchDao.getUnsyncedCompletedBatchesOnce()
            if (pendingBatches.isEmpty()) {
                logger.i("No pending HL7 batch transactions to sync")
                return
            }
            logger.i("Resending ${pendingBatches.size} pending HL7 batch transactions")
            for (batch in pendingBatches) {
                sendInventory(batchId = batch.batchId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e("resendPendingBatches failed", e, event = LogEvent.HL7_RESEND_FAILED)
        }
    }

    private data class ResolvedInventoryItem(
        val ndc: String,
        val drugId: Long,
    )

    // ─────────────────────────── INBOUND HANDLER: INR^U04/U06 (INVENTORY REQUEST) ───────────────────────────

    internal suspend fun handleInrInventoryRequest(message: HL7Message) {
        logger.i("Handling INR Inventory Request | msgId=${message.header?.messageControlId.orEmpty()}")

        // INV segments carry the substance / drug info in an INR message
        val invSegments = message.segments<INVSegment>(INVSegment.NAME)

        val resolvedItems = mutableListOf<ResolvedInventoryItem>()

        if (invSegments.isNotEmpty()) {
            for (inv in invSegments) {
                val ndc = inv.deviceItemCode.trim()

                if (ndc.isBlank()) {
                    logger.w("Skipping INV segment — NDC is blank")
                    continue
                }

                resolveInventoryItem(
                    ndc = ndc,
                    fallbackName = inv.deviceItemName,
                )?.let { resolvedItems.add(it) }
            }
        } else {
            // No INV segments — some PMS senders (e.g. this INR^U04 variant) carry the
            // substance data in the RXE segment instead.
            val rxe = message.segment<RXESegment>(RXESegment.NAME)
            val ndc = rxe?.giveCode?.trim().orEmpty()
            if (rxe == null || ndc.isBlank()) {
                logger.w("No INV or usable RXE segment found in HL7 message — ignoring")
                return
            }

            resolveInventoryItem(
                ndc = ndc,
                fallbackName = rxe.giveName,
            )?.let { resolvedItems.add(it) }
        }

        if (resolvedItems.isEmpty()) {
            logger.w("No valid drugs resolved from local DB or API. Batch will not be created.")
            notifier.show(
                title = context.getString(R.string.hl7_notification_inventory_title),
                message = context.getString(R.string.hl7_notification_inventory_no_drugs)
            )
            return
        }

        val now = System.currentTimeMillis()
        val batch = StockBatchEntity(
            batchId = now,
            startDateTime = now,
            endDateTime = null,
            status = BatchStatus.INPROGRESS,
            isDeleted = false,
            note = null,
            bucketId = "",
            requestIdFromPMS = message.header?.messageControlId.orEmpty()
        )
        val batchId = stockBatchDao.insert(batch)

        for (item in resolvedItems) {
            val txn = StockTxnEntity(
                drugId = item.drugId,
                status = CountStatus.PARTIAL,
                batchId = batchId
            )

            val txnId = stockTxnDao.upsertPreservingId(txn)

            logger.i(
                "Inserted stock txn for NDC: ${item.ndc}, drugId: ${item.drugId}, txnId: $txnId"
            )
        }

        // Stock txns added → persist the batch's live NDC total and running user.
        stockTxnDao.refreshBatchTotalNdcs(batchId)
        stockTxnDao.updateBatchUserName(batchId, operatorNameProvider().display())

        logger.i("Processed ${resolvedItems.size} inventory items for batchId: $batchId")

        notifier.show(
            title = context.getString(R.string.hl7_notification_inventory_title),
            message = context.getString(R.string.hl7_notification_inventory_items_count, resolvedItems.size)
        )
    }

    /**
     * Resolves a single inventory NDC to a [ResolvedInventoryItem], checking the local
     * DB first and falling back to the drug-info API (saving the result locally) when
     * not found. Returns null when the drug can't be resolved by either path.
     */
    private suspend fun resolveInventoryItem(
        ndc: String,
        fallbackName: String,
    ): ResolvedInventoryItem? {
        val name = fallbackName.takeIf { it.isNotBlank() }
        // A local DB error skips this item instead of failing the whole inventory message.
        val lookup = try {
            drugResolver.resolve(ndc, expectedNdc = ndc, nameIfMissing = name, nameIfUnknown = name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e("Failed to look up local drug for NDC: $ndc", e, event = LogEvent.DRUG_LOOKUP_FAILED)
            return null
        }
        if (lookup !is DrugLookup.Found) {
            logger.w("Skipping inventory item for NDC: $ndc → $lookup")
            return null
        }
        return ResolvedInventoryItem(
            ndc = ndc,
            drugId = lookup.drug.drugId,
        )
    }
}
