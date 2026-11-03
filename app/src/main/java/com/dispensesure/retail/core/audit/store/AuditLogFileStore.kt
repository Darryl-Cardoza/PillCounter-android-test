package com.dispensesure.retail.core.audit.store

import com.dispensesure.retail.core.audit.AuditLogConfig
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.time.Clock
import java.time.LocalDate

/**
 * Home of the audit lines, one JSON record each: one `<UTC date>-<seq>.log` per day, a new seq when one fills.
 * Not thread-safe on purpose; only the single AuditLogger worker writes. The uploader only touches closed files.
 */
class AuditLogFileStore(
    private val directory: File,
    private val segmentSlot: AuditSegmentSlot,
    private val outbox: AuditOutbox,
    private val clock: Clock = Clock.systemUTC(),
    private val maxFileBytes: Long = AuditLogConfig.MAX_FILE_BYTES
) {

    /** Appends one line, first starting a new empty file when the UTC day changed or the line won't fit. True when that closed a file. */
    fun append(line: String): Boolean {
        directory.mkdirs()
        val lineBytes = "$line\n".toByteArray(Charsets.UTF_8)
        val today = LocalDate.now(clock)
        val current = segmentSlot.load()
        val target = if (current != null && current.logDate == today && !wouldOverflow(current, lineBytes.size)) {
            current
        } else {
            openNextSegment(current, today)
        }
        FileOutputStream(localFile(target), true).use { it.write(lineBytes) }
        return current != null && target != current && localFile(current).exists()
    }

    /** Moves the writer to a new slot so the current file can be prepared. True when a file was closed. */
    fun closeActiveFile(onlyIfDayEnded: Boolean): Boolean {
        val current = segmentSlot.load() ?: return false
        if (!localFile(current).exists()) return false
        val today = LocalDate.now(clock)
        if (onlyIfDayEnded && current.logDate == today) return false
        openNextSegment(current, today)
        return true
    }

    /** A kill mid-write leaves half a line; cut it off so the file holds only whole records. True when it cut. */
    fun dropIncompleteLine(): Boolean {
        val file = segmentSlot.load()?.let(::localFile) ?: return false
        if (!file.exists() || file.length() == 0L) return false
        RandomAccessFile(file, "rw").use { access ->
            var end = access.length()
            access.seek(end - 1)
            if (access.read() == '\n'.code) return false
            // Walk back to the last newline; everything after it is the half line.
            while (end > 0) {
                access.seek(end - 1)
                if (access.read() == '\n'.code) break
                end--
            }
            access.setLength(end)
        }
        return true
    }

    fun readableFilesOldestFirst(): List<File> = localSegmentsOldestFirst().map { it.second }

    /** Files the writer has moved past, oldest first; the uploader prepares these. */
    fun closedFilesOldestFirst(): List<Pair<SegmentId, File>> {
        // List before reading the slot: the writer saves a slot before creating its file,
        // so the file being written is never mistaken for a closed one.
        val localSegments = localSegmentsOldestFirst()
        val activeSegment = segmentSlot.load()
        return localSegments.filter { it.first != activeSegment }
    }

    private fun openNextSegment(previous: SegmentId?, today: LocalDate): SegmentId {
        val first = if (previous != null && previous.logDate == today) previous.next() else SegmentId(today, 1)
        // Skip slots still on disk, e.g. after the device clock went back.
        val next = generateSequence(first, SegmentId::next).first { candidate ->
            !localFile(candidate).exists() && !outbox.isTaken(candidate)
        }
        segmentSlot.save(next)
        return next
    }

    private fun wouldOverflow(segmentId: SegmentId, extraBytes: Int): Boolean {
        val file = localFile(segmentId)
        return file.exists() && file.length() + extraBytes > maxFileBytes
    }

    private fun localSegmentsOldestFirst(): List<Pair<SegmentId, File>> =
        directory.listFiles().orEmpty()
            .filter { it.isFile }
            .mapNotNull { file -> SegmentId.fromFileName(file.name)?.let { segmentId -> segmentId to file } }
            .sortedBy { it.first }

    private fun localFile(segmentId: SegmentId) = File(directory, segmentId.fileName)
}
