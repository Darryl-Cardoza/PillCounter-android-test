package com.dispensesure.retail.core.audit.upload

import com.dispensesure.retail.core.audit.AuditLogConfig
import com.dispensesure.retail.core.audit.AuditLogger
import com.dispensesure.retail.core.audit.store.OutboxSegment
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody

/** The multipart body for POST /audit/logs. Every number goes as a text part, as the API asks. */
internal object AuditUploadForm {
    private val OCTET_STREAM = "application/octet-stream".toMediaType()

    fun build(segment: OutboxSegment, sequence: Int, deviceKey: String): MultipartBody {
        val meta = segment.meta
        val uploadFileName = segment.id.copy(sequence = sequence).fileName
        return MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", uploadFileName, segment.sealedFile.asRequestBody(OCTET_STREAM))
            .addFormDataPart("device_key", deviceKey)
            .addFormDataPart("log_date", segment.id.logDate.toString())
            .addFormDataPart("sequence", sequence.toString())
            .addFormDataPart("sha256", meta.sha256)
            .addFormDataPart("app_name", AuditLogConfig.APP_NAME)
            .addFormDataPart("app_version", AuditLogger.APP_VERSION)
            .addFormDataPart("chain_last_hash", meta.chainLastHash)
            .addFormDataPart("dek_key_id", meta.dekKeyId)
            .addFormDataPart("dek_version", meta.dekVersion.toString())
            .addFormDataPart("event_count", meta.eventCount.toString())
            .apply {
                meta.firstEventAt?.let { addFormDataPart("first_event_at", it) }
                meta.lastEventAt?.let { addFormDataPart("last_event_at", it) }
            }
            .build()
    }
}
