package com.dispensesure.retail.core.room.models.dtos

/**
 * One dispense txn or stock batch and whether it reached the PMS, read just before a delete
 * so the audit log can name what was removed.
 */
data class RecordSyncState(
    val id: Long,
    val isSynced: Boolean?,
    val isCompleted: Boolean
)

/** Completed records that were never sent: the ones a delete loses for good. */
fun List<RecordSyncState>.unsyncedIds(): List<Long> =
    filter { it.isCompleted && it.isSynced != true }.map { it.id }
