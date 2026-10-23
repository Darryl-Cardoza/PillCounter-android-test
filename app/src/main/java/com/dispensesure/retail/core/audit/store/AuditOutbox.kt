package com.dispensesure.retail.core.audit.store

import com.dispensesure.retail.core.audit.AuditLogConfig
import com.dispensesure.retail.core.audit.model.AuditSegmentMeta
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

data class OutboxSegment(val id: SegmentId, val sealedFile: File, val meta: AuditSegmentMeta)

/** Prepared files waiting to upload (`outbox/`) and ones the server refused for good (`failed/`), each with a `.meta.json`. */
class AuditOutbox(auditDirectory: File) {

    private val outboxDirectory = File(auditDirectory, AuditLogConfig.OUTBOX_DIRECTORY_NAME)
    private val failedDirectory = File(auditDirectory, AuditLogConfig.FAILED_DIRECTORY_NAME)

    fun contains(segmentId: SegmentId): Boolean = File(outboxDirectory, segmentId.fileName).exists()

    /** True while the slot is still on disk here, so the writer must not reuse it. */
    fun isTaken(segmentId: SegmentId): Boolean =
        contains(segmentId) || File(failedDirectory, segmentId.fileName).exists()

    /** Meta first, sealed bytes last, each via a temp file: a sealed file in the outbox is always complete. */
    fun put(segmentId: SegmentId, sealedBytes: ByteArray, meta: AuditSegmentMeta) {
        outboxDirectory.mkdirs()
        writeAtomically(metaFile(outboxDirectory, segmentId), Json.encodeToString(AuditSegmentMeta.serializer(), meta).toByteArray())
        writeAtomically(File(outboxDirectory, segmentId.fileName), sealedBytes)
    }

    fun segmentsOldestFirst(): List<OutboxSegment> =
        outboxDirectory.listFiles().orEmpty()
            .mapNotNull { file ->
                val segmentId = SegmentId.fromFileName(file.name) ?: return@mapNotNull null
                readMeta(segmentId)?.let { meta -> OutboxSegment(segmentId, file, meta) }
            }
            .sortedBy { it.id }

    fun delete(segment: OutboxSegment) {
        segment.sealedFile.delete()
        metaFile(outboxDirectory, segment.id).delete()
    }

    fun moveToFailed(segment: OutboxSegment) {
        failedDirectory.mkdirs()
        metaFile(outboxDirectory, segment.id).renameTo(metaFile(failedDirectory, segment.id))
        segment.sealedFile.renameTo(File(failedDirectory, segment.id.fileName))
    }

    fun failedCount(): Int = failedDirectory.listFiles().orEmpty().count { SegmentId.fromFileName(it.name) != null }

    private fun readMeta(segmentId: SegmentId): AuditSegmentMeta? =
        runCatching {
            Json.decodeFromString(AuditSegmentMeta.serializer(), metaFile(outboxDirectory, segmentId).readText())
        }.getOrNull()

    private fun metaFile(directory: File, segmentId: SegmentId) =
        File(directory, segmentId.fileName + AuditLogConfig.META_FILE_SUFFIX)

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tempFile = File(target.parentFile, target.name + AuditLogConfig.TEMP_FILE_SUFFIX)
        FileOutputStream(tempFile).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        check(tempFile.renameTo(target)) { "Could not move ${tempFile.name} into place" }
    }
}
