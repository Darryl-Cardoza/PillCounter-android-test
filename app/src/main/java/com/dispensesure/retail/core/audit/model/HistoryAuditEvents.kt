package com.dispensesure.retail.core.audit.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class PurgeReason { RETENTION_PERIOD, LOCAL_STORAGE_NOT_ALLOWED, SYNCED_DATA_CLEANUP }

@Serializable
@SerialName("HISTORY_DETAIL_VIEWED")
data class HistoryDetailViewed(@SerialName("txn_id") val txnId: Long) : AuditLogEvent()

@Serializable
@SerialName("HISTORY_TXN_DELETED")
data class HistoryTxnDeleted(
    @SerialName("txn_id") val txnId: Long,
    @SerialName("was_unsynced") val wasUnsynced: Boolean
) : AuditLogEvent()

/** Range bounds are epoch ms, as the delete query uses them. */
@Serializable
@SerialName("HISTORY_BULK_DELETED")
data class HistoryBulkDeleted(
    @SerialName("range_start") val rangeStart: Long,
    @SerialName("range_end") val rangeEnd: Long,
    @SerialName("status_filter") val statusFilter: String,
    val count: Int,
    @SerialName("txn_ids") val txnIds: List<Long>,
    @SerialName("unsynced_ids") val unsyncedIds: List<Long>
) : AuditLogEvent()

/** Range is null for a single delete from the batch detail screen. */
@Serializable
@SerialName("BATCH_DELETED")
data class BatchDeleted(
    @SerialName("batch_ids") val batchIds: List<Long>,
    @SerialName("range_start") val rangeStart: Long?,
    @SerialName("range_end") val rangeEnd: Long?,
    @SerialName("unsynced_ids") val unsyncedIds: List<Long>
) : AuditLogEvent()

@Serializable
@SerialName("DATA_PURGED")
data class DataPurged(
    val reason: PurgeReason,
    val count: Int,
    @SerialName("txn_ids") val txnIds: List<Long>,
    @SerialName("unsynced_ids") val unsyncedIds: List<Long>
) : AuditLogEvent()
