package com.dispensesure.retail.core.audit.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class BatchSource { PMS, MANUAL, LOOSE_COUNT }

@Serializable
enum class BatchSyncOutcome { COMPLETE, STOPPED, FAILED }

@Serializable
@SerialName("PMS_REQUEST_RECEIVED")
data class PmsRequestReceived(
    @SerialName("control_id") val controlId: String?,
    @SerialName("drug_count") val drugCount: Int,
    @SerialName("resolved_count") val resolvedCount: Int
) : AuditLogEvent()

/** requested_ndcs is filled for PMS batches only. */
@Serializable
@SerialName("BATCH_CREATED")
data class BatchCreated(
    @SerialName("batch_id") val batchId: Long,
    val source: BatchSource,
    val bucket: String?,
    @SerialName("requested_ndcs") val requestedNdcs: List<String>
) : AuditLogEvent()

/** old_bottles is null when the scan added a new bottle line. */
@Serializable
@SerialName("BOTTLE_COUNTED")
data class BottleCounted(
    @SerialName("batch_id") val batchId: Long,
    val ndc: String,
    val lot: String?,
    val expiry: String?,
    val serial: String?,
    @SerialName("old_bottles") val oldBottleQty: Int?,
    @SerialName("new_bottles") val newBottleQty: Int
) : AuditLogEvent()

@Serializable
@SerialName("BOTTLE_COUNT_ADJUSTED")
data class BottleCountAdjusted(
    @SerialName("batch_id") val batchId: Long,
    val ndc: String,
    val lot: String?,
    @SerialName("old_bottles") val oldBottleQty: Int?,
    @SerialName("new_bottles") val newBottleQty: Int
) : AuditLogEvent()

@Serializable
@SerialName("BOTTLE_LINE_DELETED")
data class BottleLineDeleted(
    @SerialName("batch_id") val batchId: Long,
    @SerialName("line_id") val lineId: Long,
    val ndc: String,
    val lot: String?,
    @SerialName("old_qty") val oldBottleQty: Int?
) : AuditLogEvent()

@Serializable
@SerialName("LINE_EDITED")
data class LineEdited(
    @SerialName("batch_id") val batchId: Long,
    @SerialName("line_id") val lineId: Long,
    val lot: String?,
    @SerialName("old_sealed") val oldBottleQty: Int?,
    @SerialName("new_sealed") val newBottleQty: Int?,
    @SerialName("old_open") val oldLooseQty: Int?,
    @SerialName("new_open") val newLooseQty: Int?
) : AuditLogEvent()

@Serializable
@SerialName("LINE_DELETED")
data class LineDeleted(
    @SerialName("batch_id") val batchId: Long,
    @SerialName("line_id") val lineId: Long,
    val ndc: String?,
    val lot: String?,
    val expiry: String?,
    @SerialName("old_sealed") val oldBottleQty: Int?,
    @SerialName("old_open") val oldLooseQty: Int?
) : AuditLogEvent()

@Serializable
@SerialName("LOOSE_COUNT_SAVED")
data class LooseCountSaved(
    val ndc: String?,
    val lot: String?,
    @SerialName("loose_total") val looseQty: Int,
    @SerialName("batch_id") val batchId: Long
) : AuditLogEvent()

/** The note itself is never recorded, only its length; 0 means no note. */
@Serializable
@SerialName("BATCH_COMPLETED")
data class BatchCompleted(
    @SerialName("batch_id") val batchId: Long,
    @SerialName("ndc_count") val ndcCount: Int,
    @SerialName("total_pills") val totalPills: Int,
    @SerialName("note_length") val noteLength: Int
) : AuditLogEvent()

/** chunk_index / total_chunks are set when the send stopped part-way. */
@Serializable
@SerialName("BATCH_SYNCED")
data class BatchSynced(
    @SerialName("batch_id") val batchId: Long,
    val trigger: SendTrigger,
    val outcome: BatchSyncOutcome,
    @SerialName("chunk_index") val chunkIndex: Int?,
    @SerialName("total_chunks") val totalChunks: Int?
) : AuditLogEvent() {
    override val result: AuditResult
        get() = if (outcome == BatchSyncOutcome.COMPLETE) AuditResult.SUCCESS else AuditResult.FAILURE
}
