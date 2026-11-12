package com.dispensesure.retail.core.audit.store

import com.dispensesure.retail.core.audit.AuditLogConfig
import java.time.LocalDate
import java.util.Locale

/** One file slot: the UTC day and the file number within it. Also names the file, on disk and on upload. */
data class SegmentId(val logDate: LocalDate, val sequence: Int) : Comparable<SegmentId> {

    /** e.g. `2026-10-22-0001.log`. */
    val fileName: String
        get() = "$logDate-${String.format(Locale.ROOT, "%04d", sequence)}.${AuditLogConfig.FILE_EXTENSION}"

    fun next(): SegmentId = copy(sequence = sequence + 1)

    override fun compareTo(other: SegmentId): Int =
        compareValuesBy(this, other, SegmentId::logDate, SegmentId::sequence)

    companion object {
        private val FILE_NAME = Regex("""(\d{4}-\d{2}-\d{2})-(\d{4})\.${AuditLogConfig.FILE_EXTENSION}""")

        /** Reads a file name; null for anything else, such as the old `audit-logs-1.log`. */
        fun fromFileName(fileName: String): SegmentId? =
            FILE_NAME.matchEntire(fileName)?.let { match ->
                runCatching { SegmentId(LocalDate.parse(match.groupValues[1]), match.groupValues[2].toInt()) }.getOrNull()
            }
    }
}
