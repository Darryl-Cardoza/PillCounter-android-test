package com.dispensesure.retail.core.audit.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ResumeSource { QUEUE, RX_SCAN }

@Serializable
enum class TxnSource { PMS, STANDALONE }

@Serializable
enum class VialPhotoMode { AUTO, MANUAL }

@Serializable
enum class Hl7SendOutcome { ACCEPTED, REJECTED, FAILED, SKIPPED_NO_DRUG }

@Serializable
@SerialName("DISPENSE_RESUMED")
data class DispenseResumed(
    @SerialName("txn_id") val txnId: Long,
    val source: ResumeSource,
    val step: String
) : AuditLogEvent()

/** format is ZUI, ZNI or RXE. */
@Serializable
@SerialName("PMS_ORDER_RECEIVED")
data class PmsOrderReceived(
    @SerialName("message_id") val messageId: String?,
    @SerialName("order_control") val orderControl: String?,
    val format: String
) : AuditLogEvent()

@Serializable
@SerialName("DISPENSE_TXN_CREATED")
data class DispenseTxnCreated(
    @SerialName("txn_id") val txnId: Long,
    val source: TxnSource,
    @SerialName("message_id") val messageId: String?,
    val ndc: String?,
    @SerialName("target_qty") val targetQty: Int?,
    val priority: String?
) : AuditLogEvent()

/** A PMS new order for a fill that is still open updates that txn instead of creating one. */
@Serializable
@SerialName("DISPENSE_TXN_UPDATED")
data class DispenseTxnUpdated(
    @SerialName("txn_id") val txnId: Long,
    @SerialName("message_id") val messageId: String?,
    val ndc: String?,
    @SerialName("target_qty") val targetQty: Int?,
    val priority: String?
) : AuditLogEvent()

@Serializable
@SerialName("ORDER_HELD")
data class OrderHeld(
    @SerialName("order_id") val orderId: String,
    @SerialName("rows_changed") val rowsChanged: Int
) : AuditLogEvent()

@Serializable
@SerialName("ORDER_RELEASED")
data class OrderReleased(
    @SerialName("order_id") val orderId: String,
    @SerialName("old_status") val oldStatus: String?,
    @SerialName("rows_changed") val rowsChanged: Int
) : AuditLogEvent()

/** Names the cancelled txns by id only; the Rx number is never recorded. */
@Serializable
@SerialName("ORDER_CANCELLED")
data class OrderCancelled(@SerialName("txn_ids") val txnIds: List<Long>) : AuditLogEvent()

@Serializable
@SerialName("ORDER_DISCONTINUED")
data class OrderDiscontinued(
    @SerialName("order_id") val orderId: String,
    @SerialName("rows_changed") val rowsChanged: Int
) : AuditLogEvent()

@Serializable
@SerialName("ORDER_EDITED")
data class OrderEdited(
    @SerialName("txn_id") val txnId: Long,
    val changes: List<FieldChange>,
    val restored: Boolean
) : AuditLogEvent()

/** txn_id is null for a standalone scan, which has no order until Proceed. */
@Serializable
@SerialName("RX_SCANNED")
data class RxScanned(
    @SerialName("txn_id") val txnId: Long?,
    @SerialName("label_ndc") val labelNdc: String?,
    val qty: Int?
) : AuditLogEvent()

@Serializable
@SerialName("SUBSTITUTE_ACCEPTED")
data class SubstituteAccepted(
    @SerialName("txn_id") val txnId: Long,
    @SerialName("expected_ndc") val expectedNdc: String?,
    @SerialName("scanned_ndc") val scannedNdc: String?
) : AuditLogEvent()

@Serializable
@SerialName("COUNT_DELETED")
data class CountDeleted(
    @SerialName("txn_id") val txnId: Long,
    val step: String?,
    @SerialName("count_id") val countId: Long,
    @SerialName("count") val deletedPillCount: Int?
) : AuditLogEvent()

@Serializable
@SerialName("VIAL_PHOTO_SAVED")
data class VialPhotoSaved(
    @SerialName("txn_id") val txnId: Long,
    val mode: VialPhotoMode
) : AuditLogEvent()

@Serializable
@SerialName("DISPENSE_RESET")
data class DispenseReset(
    @SerialName("txn_id") val txnId: Long,
    @SerialName("step_reached") val stepReached: String?,
    @SerialName("counts_removed") val countsRemoved: Int
) : AuditLogEvent()

/** The note itself is never recorded, only its length. */
@Serializable
@SerialName("NOTE_ADDED")
data class NoteAdded(
    @SerialName("txn_id") val txnId: Long,
    @SerialName("note_length") val noteLength: Int
) : AuditLogEvent()

@Serializable
@SerialName("NOTE_SKIPPED")
data class NoteSkipped(@SerialName("txn_id") val txnId: Long) : AuditLogEvent()

@Serializable
@SerialName("DISPENSE_COMPLETED")
data class DispenseCompleted(
    @SerialName("txn_id") val txnId: Long,
    val status: String,
    val dispensed: Int,
    val target: Int?
) : AuditLogEvent()

@Serializable
@SerialName("HL7_DISPENSE_SENT")
data class Hl7DispenseSent(
    @SerialName("txn_id") val txnId: Long,
    @SerialName("control_id") val controlId: String?,
    val trigger: SendTrigger,
    val outcome: Hl7SendOutcome
) : AuditLogEvent() {
    override val result: AuditResult
        get() = if (outcome == Hl7SendOutcome.ACCEPTED) AuditResult.SUCCESS else AuditResult.FAILURE
}

/** The PMS rejected it (AR); it's left out of auto-resend until the app restarts. */
@Serializable
@SerialName("DISPENSE_SYNC_ABANDONED")
data class DispenseSyncAbandoned(
    @SerialName("txn_id") val txnId: Long,
    @SerialName("control_id") val controlId: String?
) : AuditLogEvent() {
    override val result: AuditResult get() = AuditResult.FAILURE
}
