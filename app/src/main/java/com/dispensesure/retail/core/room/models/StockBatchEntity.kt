package com.dispensesure.retail.core.room.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.dispensesure.retail.core.region.Region
import com.dispensesure.retail.core.room.di.StockBatchConverters
import com.dispensesure.retail.core.room.models.enums.BatchStatus

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
 * @property region Region the batch belongs to.
 */
@Entity(
    tableName = "stock_batch"
)
data class StockBatchEntity(
    @PrimaryKey()
    val batchId: Long = 0,

    val startDateTime: Long = System.currentTimeMillis(),
    val endDateTime: Long? = null,

    @TypeConverters(StockBatchConverters::class)
    val status: BatchStatus = BatchStatus.INPROGRESS,

    val isDeleted: Boolean = false,
    val note: String? = null,
    val bucketId: String? = null,
    val requestIdFromPMS: String? = null,
    val isSynced: Boolean = false,

    /**
     * Progress markers for a chunked HL7 inventory sync (see
     * [com.dispensesure.retail.feature.hl7.data.repository.StockHl7Repository.sendInventory]).
     * [lastAckedChunkIndex] is the highest chunk index that has received a
     * successful ACK; [totalChunks] is fixed once the sync starts. On resume,
     * sending continues at [lastAckedChunkIndex] + 1 instead of restarting from
     * chunk 1, so an already-applied chunk is never resent.
     */
    val lastAckedChunkIndex: Int = 0,
    val totalChunks: Int? = null,

    /** Number of distinct NDCs (drug groups) counted in this batch. */
    val totalNdcs: Int? = null,

    /** Display name of the user who ran the count. */
    val userName: String? = null,

    /** Region of the user who started it; lists only show the logged-in region's batches. */
    val region: Region = Region.US,

    /**
     * ZDR-15 of the PMS request, distinct from [requestIdFromPMS] (which is MSH-10). The PMS
     * correlates its inventory request to our response on this value, so it must be stored at
     * request time and echoed back — MSH-10 alone is not enough to match the two up.
     *
     * Appended last deliberately: inserting it mid-record would shift every later componentN.
     */
    val orderReferenceFromPMS: String? = null,
)
