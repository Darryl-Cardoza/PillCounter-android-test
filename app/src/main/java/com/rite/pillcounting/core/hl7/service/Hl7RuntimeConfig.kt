package com.rite.pillcounting.core.hl7.service

import android.os.Build
import com.rite.pillcounting.core.utils.preference.PreferenceHelper

/**
 * Configuration data class for HL7 service initialization
 */
data class HL7Config(
    val serverPort: Int = 2575,
    val autoResponseDelayMs: Long = 10_000L,
    val nsdBroadcastServiceName: String = "PillCounter-${Build.MODEL}" ,
    val nsdBroadcastType: String = "_pillcounting._tcp",
    val nsdDiscoveryType: String ="_ritepmsserver._tcp",
    val imageServicePort: Int = 8080,
    val hl7Version: String = PreferenceHelper.DEFAULT_HL7_VERSION,
    val bypassTls: Boolean = false,
    val useStaticPmsConnection: Boolean = false,
    val pmsIp: String? = null,
    val pmsPort: Int = 0,
) {
    companion object {
        /**
         * Single source of truth for assembling an [HL7Config] from [preferenceHelper] —
         * used both by a normal app-driven start and by the service's own START_STICKY
         * restart path, so the two can never drift out of sync with each other again.
         * [nsdBroadcastType]/[nsdDiscoveryType] are passed in rather than always read from
         * preferences, since the app-driven start sources them from already-fetched
         * in-memory state instead of re-reading prefs.
         */
        fun fromPreferences(
            preferenceHelper: PreferenceHelper,
            nsdBroadcastType: String,
            nsdDiscoveryType: String,
        ): HL7Config {
            val terminalName = preferenceHelper.getSelectedTerminalName()
                ?: "PillCounter-${Build.MODEL}"
            return HL7Config(
                serverPort = 2575,
                autoResponseDelayMs = 10_000L,
                nsdBroadcastServiceName = terminalName,
                nsdBroadcastType = nsdBroadcastType,
                nsdDiscoveryType = nsdDiscoveryType,
                imageServicePort = 8080,
                hl7Version = preferenceHelper.getHl7Version(),
                bypassTls = preferenceHelper.isBypassTlsEnabled(),
                useStaticPmsConnection = preferenceHelper.isUseStaticPmsConnection(),
                pmsIp = preferenceHelper.getPmsIP(),
                pmsPort = preferenceHelper.getPmsPort(),
            )
        }
    }
}