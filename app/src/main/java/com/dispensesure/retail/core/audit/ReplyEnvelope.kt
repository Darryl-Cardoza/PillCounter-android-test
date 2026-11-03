package com.dispensesure.retail.core.audit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.ResponseBody
import retrofit2.Response

/** Reads the backend envelope `{status, is_success, message, token, data}` for the audit upload and key replies. */
internal object ReplyEnvelope {
    fun bodyOf(response: Response<ResponseBody>): String? =
        (if (response.isSuccessful) response.body() else response.errorBody())?.use { it.string() }

    /** The `data` object, or null when the body isn't the usual envelope. */
    fun dataOf(body: String?): JsonObject? =
        body?.let { runCatching { Json.parseToJsonElement(it).jsonObject["data"] as? JsonObject }.getOrNull() }
}

internal fun JsonObject.stringField(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.intField(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull
