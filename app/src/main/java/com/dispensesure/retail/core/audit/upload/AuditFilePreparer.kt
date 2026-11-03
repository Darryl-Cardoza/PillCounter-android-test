package com.dispensesure.retail.core.audit.upload

import com.dispensesure.retail.core.audit.crypto.AuditHashChain
import com.dispensesure.retail.core.audit.crypto.Pcenc
import com.dispensesure.retail.core.audit.crypto.Sha256
import com.dispensesure.retail.core.audit.model.AuditJson
import com.dispensesure.retail.core.audit.model.AuditSegmentMeta
import com.dispensesure.retail.core.audit.store.AuditLogFileStore
import com.dispensesure.retail.core.audit.store.AuditOutbox
import com.dispensesure.retail.core.audit.store.SegmentId
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import java.io.File
import javax.inject.Inject

/**
 * Turns closed log files into upload-ready ones: hash-chain tail, the whole file sealed as PCENC with the DB DEK,
 * and the SHA-256 of the sealed bytes. Each file is prepared once; the local copy goes only after that.
 */
class AuditFilePreparer @Inject constructor(
    private val fileStore: AuditLogFileStore,
    private val outbox: AuditOutbox
) {
    private val logger = AppLogger.create<AuditFilePreparer>()

    /** A file that fails stays local and is tried again on the next drain. */
    fun prepareClosedFiles(dek: ByteArray, dekKeyId: String, dekVersion: Int) {
        fileStore.closedFilesOldestFirst().forEach { (segmentId, localFile) ->
            try {
                prepare(segmentId, localFile, dek, dekKeyId, dekVersion)
            } catch (e: Exception) {
                logger.e("Preparing ${segmentId.fileName} failed", e, event = LogEvent.AUDIT_PREPARE_FAILED)
            }
        }
    }

    private fun prepare(segmentId: SegmentId, localFile: File, dek: ByteArray, dekKeyId: String, dekVersion: Int) {
        // Prepared before a kill: sealing again would give different bytes for the same slot.
        if (outbox.contains(segmentId)) {
            localFile.delete()
            return
        }
        val plaintext = localFile.readBytes()
        val lines = String(plaintext, Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }
        val records = lines.mapNotNull { line -> runCatching { AuditJson.decodeRecord(line) }.getOrNull() }
        val sealed = Pcenc.seal(plaintext, dek, dekKeyId, dekVersion)
        val meta = AuditSegmentMeta(
            sha256 = Sha256.hexOf(sealed),
            chainLastHash = AuditHashChain.lastHash(lines),
            dekKeyId = dekKeyId,
            dekVersion = dekVersion,
            eventCount = lines.size,
            firstEventAt = records.firstOrNull()?.timestamp,
            lastEventAt = records.lastOrNull()?.timestamp
        )
        outbox.put(segmentId, sealed, meta)
        localFile.delete()
    }
}
