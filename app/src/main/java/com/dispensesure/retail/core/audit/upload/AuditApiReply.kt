package com.dispensesure.retail.core.audit.upload

import com.dispensesure.retail.core.audit.ReplyEnvelope
import com.dispensesure.retail.core.audit.intField
import com.dispensesure.retail.core.audit.stringField
import okhttp3.ResponseBody
import retrofit2.Response

/** What POST /audit/logs answered: the HTTP status plus `data.code` and `data.next_free_sequence`. */
data class AuditApiReply(
    val httpStatus: Int,
    val code: String? = null,
    val nextFreeSequence: Int? = null
) {
    companion object {
        fun from(response: Response<ResponseBody>): AuditApiReply = parse(response.code(), ReplyEnvelope.bodyOf(response))

        /** A 413 may come back without the usual envelope, so anything unreadable gives just the status. */
        fun parse(httpStatus: Int, body: String?): AuditApiReply {
            val data = ReplyEnvelope.dataOf(body) ?: return AuditApiReply(httpStatus)
            return AuditApiReply(
                httpStatus = httpStatus,
                code = data.stringField("code"),
                nextFreeSequence = data.intField("next_free_sequence")
            )
        }
    }
}
