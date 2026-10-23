package com.dispensesure.retail.feature.hl7.util

import com.dispensesure.retail.core.utils.preference.PreferenceHelper

/**
 * HL7 sending application formats supported when composing outbound messages.
 * Persisted via [PreferenceHelper.saveHl7Format]/[PreferenceHelper.getHl7Format].
 */
enum class Hl7Format(val sendingApplication: String) {
    DISPENSESURE_STANDARD("DISPENSESURE"),
    DISPENSESURE_NATIVE("DISPENSESURE"),
    EYECON("EYECON"),
    VIVID("VIVID");

    companion object {
        val DEFAULT = DISPENSESURE_STANDARD

        fun fromSendingApplication(value: String?): Hl7Format {
            if (value.isNullOrBlank()) return DEFAULT
            // Exact enum name match first ("DISPENSESURE_NATIVE", "DISPENSESURE_STANDARD", "EYECON", "VIVID")
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }?.let { return it }
            // Legacy: server sends sendingApplication value ("DISPENSESURE" → STANDARD, "EYECON", "VIVID")
            return entries.firstOrNull { it.sendingApplication.equals(value, ignoreCase = true) } ?: DEFAULT
        }
    }
}

data class HL7Config(
    val sendingApplication: String,
    val sendingFacility: String,
    val receivingApplication: String,
    val receivingFacility: String,
    val versionId: String,
    /** Which body segments (ZUI/ZNI/etc) to emit — independent of [sendingApplication], which is always this app's name. */
    val messageFormat: Hl7Format = Hl7Format.DEFAULT
) {
    companion object {
        /**
         * Sourced from app settings: the terminal name identifies this
         * station as the sending facility, and the configured PMS host name
         * is used as the receiving facility since the PMS routes by t
         * identity. Mirrors iOS `HL7Config.current`.
         */
        fun current(
            selectedTerminalName: String,
            pmsHostName: String,
            hl7Version: String = PreferenceHelper.DEFAULT_HL7_VERSION,
            hl7Format: Hl7Format = Hl7Format.DEFAULT,
            sendingApplicationName: String = Hl7Format.DISPENSESURE_STANDARD.sendingApplication
        ) = HL7Config(
            sendingApplication = sendingApplicationName,
            sendingFacility = selectedTerminalName,
            receivingApplication = "client",
            receivingFacility = pmsHostName,
            versionId = hl7Version,
            messageFormat = hl7Format
        )
    }
}
