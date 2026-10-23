package com.dispensesure.retail.core.audit.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Upload fields worked out once when a file is prepared, kept beside the prepared file. */
@Serializable
data class AuditSegmentMeta(
    @SerialName("sha256") val sha256: String,
    @SerialName("chain_last_hash") val chainLastHash: String,
    @SerialName("dek_key_id") val dekKeyId: String,
    @SerialName("dek_version") val dekVersion: Int,
    @SerialName("event_count") val eventCount: Int,
    @SerialName("first_event_at") val firstEventAt: String?,
    @SerialName("last_event_at") val lastEventAt: String?
)
