package com.dispensesure.retail.core.audit.key

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.ResponseBody
import retrofit2.Response

/** What POST /mobile/devices/keys answered: the HTTP status plus `data.code`, `data.fingerprint`, `data.next_free_version`. */
data class DeviceKeyReply(
    val httpStatus: Int,
    val code: String? = null,
    val fingerprint: String? = null,
    val nextFreeVersion: Int? = null
) {
    /** 201, or 200 when the server already holds these exact bytes. */
    fun isStored() = httpStatus == 201 || (httpStatus == 200 && code == "duplicate_replay")

    /** Another key holds this version; the same bytes can go again at [nextFreeVersion]. */
    fun isVersionConflict() = httpStatus == 409 && code == "version_conflict" && nextFreeVersion != null

    companion object {
        fun from(response: Response<ResponseBody>): DeviceKeyReply {
            val body = (if (response.isSuccessful) response.body() else response.errorBody())?.use { it.string() }
            return parse(response.code(), body)
        }

        fun parse(httpStatus: Int, body: String?): DeviceKeyReply {
            val data = body
                ?.let { runCatching { Json.parseToJsonElement(it).jsonObject["data"] as? JsonObject }.getOrNull() }
                ?: return DeviceKeyReply(httpStatus)
            return DeviceKeyReply(
                httpStatus = httpStatus,
                code = (data["code"] as? JsonPrimitive)?.contentOrNull,
                fingerprint = (data["fingerprint"] as? JsonPrimitive)?.contentOrNull,
                nextFreeVersion = (data["next_free_version"] as? JsonPrimitive)?.intOrNull
            )
        }
    }
}
