package com.dispensesure.retail.core.audit.store

import com.dispensesure.retail.core.audit.AuditLogConfig
import com.dispensesure.retail.core.audit.model.AuditSegmentMeta
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class OutboxSegment(val id: SegmentId, val sealedFile: File, val meta: AuditSegmentMeta)

/** Prepared files waiting to upload (`outbox/`) and ones the server refused for good (`failed/`), each with a `.meta.json`. */
class AuditOutbox(auditDirectory: File) {

    private val outboxDirectory = File(auditDirectory, AuditLogConfig.OUTBOX_DIRECTORY_NAME)
    private val failedDirectory = File(auditDirectory, AuditLogConfig.FAILED_DIRECTORY_NAME)
    private val failedRetriedMarker = File(auditDirectory, AuditLogConfig.FAILED_RETRIED_MARKER_NAME)

    fun contains(segmentId: SegmentId): Boolean = File(outboxDirectory, segmentId.fileName).exists()

    /** True while the slot is still on disk here, so the writer must not reuse it. */
    fun isTaken(segmentId: SegmentId): Boolean =
        contains(segmentId) || File(failedDirectory, segmentId.fileName).exists()

    /** Meta first, sealed bytes last, each via a temp file: a sealed file in the outbox is always complete. */
    fun put(segmentId: SegmentId, sealedBytes: ByteArray, meta: AuditSegmentMeta) {
        outboxDirectory.mkdirs()
        writeMeta(segmentId, meta)
        writeAtomically(File(outboxDirectory, segmentId.fileName), sealedBytes)
    }

    /** Remembers the slot a refile moved this file to, so a lost reply never sends it to the old slot again. */
    fun saveUploadSequence(segment: OutboxSegment, sequence: Int) {
        writeMeta(segment.id, segment.meta.copy(uploadSequence = sequence))
    }

    /** Once per app version, sends `failed/` files again: a new build may fix why they were refused. */
    fun retryFailedOnce(appVersion: String) {
        if (failedRetriedMarker.exists() && failedRetriedMarker.readText() == appVersion) return
        outboxDirectory.mkdirs()
        failedDirectory.listFiles().orEmpty().mapNotNull { SegmentId.fromFileName(it.name) }.forEach { segmentId ->
            // Meta first, as in put: a sealed file in the outbox always has its meta.
            metaFile(failedDirectory, segmentId).renameTo(metaFile(outboxDirectory, segmentId))
            File(failedDirectory, segmentId.fileName).renameTo(File(outboxDirectory, segmentId.fileName))
        }
        writeAtomically(failedRetriedMarker, appVersion.toByteArray())
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

    private fun writeMeta(segmentId: SegmentId, meta: AuditSegmentMeta) =
        writeAtomically(metaFile(outboxDirectory, segmentId), Json.encodeToString(AuditSegmentMeta.serializer(), meta).toByteArray())

    private fun metaFile(directory: File, segmentId: SegmentId) =
        File(directory, segmentId.fileName + AuditLogConfig.META_FILE_SUFFIX)

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tempFile = File(target.parentFile, target.name + AuditLogConfig.TEMP_FILE_SUFFIX)
        FileOutputStream(tempFile).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        // Replaces an existing target in one step; File.renameTo can't overwrite on every platform.
        Files.move(tempFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
