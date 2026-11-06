package com.dispensesure.retail.core.audit.key

import com.dispensesure.retail.core.audit.ReplyEnvelope
import com.dispensesure.retail.core.audit.intField
import com.dispensesure.retail.core.audit.stringField
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
        fun from(response: Response<ResponseBody>): DeviceKeyReply = parse(response.code(), ReplyEnvelope.bodyOf(response))

        fun parse(httpStatus: Int, body: String?): DeviceKeyReply {
            val data = ReplyEnvelope.dataOf(body) ?: return DeviceKeyReply(httpStatus)
            return DeviceKeyReply(
                httpStatus = httpStatus,
                code = data.stringField("code"),
                fingerprint = data.stringField("fingerprint"),
                nextFreeVersion = data.intField("next_free_version")
            )
        }
    }
}
