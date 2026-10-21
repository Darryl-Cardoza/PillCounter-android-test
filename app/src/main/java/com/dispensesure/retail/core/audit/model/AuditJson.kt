package com.dispensesure.retail.core.audit.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The one Json setup for audit records and events. */
internal object AuditJson {
    private const val TAG_KEY = "tag"

    private val json = Json { classDiscriminator = TAG_KEY }

    fun encodeRecord(record: AuditRecord): String = json.encodeToString(AuditRecord.serializer(), record)

    fun decodeRecord(recordJson: String): AuditRecord = json.decodeFromString(AuditRecord.serializer(), recordJson)

    /** Splits an event into its tag (the @SerialName) and its own fields. */
    fun tagAndData(event: AuditLogEvent): Pair<String, JsonObject> {
        val eventJson = json.encodeToJsonElement(AuditLogEvent.serializer(), event).jsonObject
        return eventJson.getValue(TAG_KEY).jsonPrimitive.content to JsonObject(eventJson - TAG_KEY)
    }
}
