package com.rite.pillcounting.core.security

data class AuditEvent(
    val timestampMs: Long,
    val checkName: String,
    val passed: Boolean,
    val detail: String = ""
) {
    // Serializes to a single append-friendly line
    fun toLogLine(): String =
        "$timestampMs|$checkName|${if (passed) "PASS" else "FAIL"}|$detail\n"

    companion object {
        fun fromLogLine(line: String): AuditEvent? {
            val parts = line.trim().split("|")
            if (parts.size < 3) return null
            return AuditEvent(
                timestampMs = parts[0].toLongOrNull() ?: return null,
                checkName   = parts[1],
                passed      = parts[2] == "PASS",
                detail      = parts.getOrElse(3) { "" }
            )
        }
    }
}