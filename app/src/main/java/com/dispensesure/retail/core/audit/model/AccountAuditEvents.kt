package com.dispensesure.retail.core.audit.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("PROFILE_UPDATED")
data class ProfileUpdated(val changes: List<FieldChange>) : AuditLogEvent()

@Serializable
@SerialName("TERMINAL_CHANGED")
data class TerminalChanged(
    @SerialName("old_terminal_id") val oldTerminalId: String?,
    @SerialName("old_terminal_name") val oldTerminalName: String?,
    @SerialName("new_terminal_id") val newTerminalId: String?,
    @SerialName("new_terminal_name") val newTerminalName: String?,
    @SerialName("device_key") val deviceKey: String?
) : AuditLogEvent()

@Serializable
@SerialName("ACCOUNT_DELETED")
data class AccountDeleted(
    @SerialName("user_id") val userId: String?,
    val email: String?,
    @SerialName("unsynced_dispense_ids") val unsyncedDispenseIds: List<Long>,
    @SerialName("unsynced_batch_ids") val unsyncedBatchIds: List<Long>
) : AuditLogEvent()
