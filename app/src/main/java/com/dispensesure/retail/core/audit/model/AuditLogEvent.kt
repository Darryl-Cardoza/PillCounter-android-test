package com.dispensesure.retail.core.audit.model

import kotlinx.serialization.Serializable

/**
 * Base of every audit event. A subclass's @SerialName is its audit tag and its fields are the
 * record's `data`, so each event can carry only what audit-logs.md lists for it.
 */
@Serializable
sealed class AuditLogEvent {
    // Not serialized into data: a getter with no backing field.
    // Failure events must override it; everything else is SUCCESS.
    open val result: AuditResult get() = AuditResult.SUCCESS
}
