package com.rite.pillcounting.feature.dashboard.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.ToJson

/**
 * Request body for updating terminal settings.
 *
 * `deviceKey` has no default — a caller must always state its release intent explicitly
 * rather than silently sending `device_key: null` (which the server treats as a release;
 * see [TerminalUpdateRequestAdapter] for why that null still needs to reach the wire).
 */
@JsonClass(generateAdapter = true)
data class TerminalUpdateRequest(
    @Json(name = "terminal_name") val terminalName: String,
    @Json(name = "is_active") val isActive: Boolean,
    @Json(name = "device_key") val deviceKey: String?
)

/**
 * The app's Moshi instance is reflection-based (no moshi-kotlin-codegen processor wired),
 * and [KotlinJsonAdapterFactory][com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory]
 * omits nullable fields that are null instead of writing them as JSON `null`. That would
 * silently break a future terminal-release call (server never sees `device_key` cleared →
 * next claim 409s). This adapter forces `device_key` to always serialize, including when null.
 */
class TerminalUpdateRequestAdapter {
    @ToJson
    fun toJson(writer: JsonWriter, value: TerminalUpdateRequest) {
        val originalSerializeNulls = writer.serializeNulls
        writer.serializeNulls = true
        writer.beginObject()
        writer.name("terminal_name").value(value.terminalName)
        writer.name("is_active").value(value.isActive)
        writer.name("device_key").value(value.deviceKey)
        writer.endObject()
        writer.serializeNulls = originalSerializeNulls
    }
}

