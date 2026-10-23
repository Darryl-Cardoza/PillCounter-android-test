package com.dispensesure.retail.core.audit.upload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.ResponseBody
import retrofit2.Response

/** What POST /audit/logs answered: the HTTP status plus `data.code` and `data.next_free_sequence`. */
data class AuditApiReply(
    val httpStatus: Int,
    val code: String? = null,
    val nextFreeSequence: Int? = null
) {
    companion object {
        fun from(response: Response<ResponseBody>): AuditApiReply {
            val body = (if (response.isSuccessful) response.body() else response.errorBody())?.use { it.string() }
            return parse(response.code(), body)
        }

        /** A 413 may come back without the usual envelope, so anything unreadable gives just the status. */
        fun parse(httpStatus: Int, body: String?): AuditApiReply {
            val data = body
                ?.let { runCatching { Json.parseToJsonElement(it).jsonObject["data"] as? JsonObject }.getOrNull() }
                ?: return AuditApiReply(httpStatus)
            return AuditApiReply(
                httpStatus = httpStatus,
                code = (data["code"] as? JsonPrimitive)?.contentOrNull,
                nextFreeSequence = (data["next_free_sequence"] as? JsonPrimitive)?.intOrNull
            )
        }
    }
}
