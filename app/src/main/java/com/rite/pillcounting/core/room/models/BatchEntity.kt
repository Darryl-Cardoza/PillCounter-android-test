package com.rite.pillcounting.core.room.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.rite.pillcounting.core.room.di.BatchConverters
import com.rite.pillcounting.core.room.models.enums.BatchStatus

/**
 * Entity representing a batch for pill counting operations.
 *
 * A batch groups multiple pill count transactions together and tracks the
 * overall progress and metadata for a counting session.
 *
 * @property batchId Auto-generated primary key (batch identifier).
 * @property startDateTime Timestamp when the batch was created (epoch millis). Defaults to current time.
 * @property endDateTime Timestamp when the batch was completed (epoch millis). Null if still in progress.
 * @property status Current status of the batch (INPROGRESS or COMPLETED).
 * @property isDeleted Soft-delete flag. Defaults to false.
 * @property note Optional free-form note or description for the batch.
 * @property bucketId Optional reference to a container/bucket ID associated with this batch.
 * @property createdAt Creation timestamp (epoch millis).
 * @property updatedAt Last update timestamp (epoch millis).
 */
@Entity(
    tableName = "batch"
)
data class BatchEntity(
    @PrimaryKey()
    val batchId: Long = 0,

    val startDateTime: Long = System.currentTimeMillis(),
    val endDateTime: Long? = null,

    @TypeConverters(BatchConverters::class)
    val status: BatchStatus = BatchStatus.INPROGRESS,

    val isDeleted: Boolean = false,
    val note: String? = null,
    val bucketId: String? = null,
    val requestIdFromPMS: String? = null,
    val isSynced: Boolean = false
)