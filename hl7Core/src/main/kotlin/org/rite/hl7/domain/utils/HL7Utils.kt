package org.rite.hl7.domain.utils

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.rite.hl7.hl7.domain.utils.HL7Constants
import org.rite.hl7.util.currentLocalDateTime


object HL7Utils {

    private val mutex = Mutex()
    private var counter: Int = 0
    private const val MAX_COUNTER = 999

    /**
     * Generate unique HL7 Message Control ID (MSH-10)
     */
    suspend fun generateMessageControlId(): String =
        mutex.withLock {
            val timestamp = currentLocalDateTime()
            counter = if (counter >= MAX_COUNTER) 0 else counter + 1
            timestamp + counter.toString().padStart(3, '0')
        }



    /**
     * Escape HL7 special characters
     */
    fun escapeHL7Text(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        return text
            .replace("\\", "\\E\\")
            .replace("|", "\\F\\")
            .replace("^", "\\S\\")
            .replace("&", "\\T\\")
            .replace("~", "\\R\\")
    }

    fun buildField(value: String?): String = value ?: ""

    fun buildComponent(vararg parts: String?): String =
        parts.joinToString(HL7Constants.COMPONENT_SEPARATOR) { it ?: "" }

    fun buildSegment(segmentType: String, vararg fields: String): String =
        buildString {
            append(segmentType)
            append(HL7Constants.FIELD_SEPARATOR)
            append(fields.joinToString(HL7Constants.FIELD_SEPARATOR))
        }
}
