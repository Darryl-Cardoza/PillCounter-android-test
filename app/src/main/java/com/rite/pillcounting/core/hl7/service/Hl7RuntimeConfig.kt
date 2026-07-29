package com.rite.pillcounting.core.hl7.service

import android.os.Build

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
    val imageServiceSecurePort: Int = 8443,
    val hl7Version: String = DEFAULT_HL7_VERSION,
    val bypassTls: Boolean = false,
    val useStaticPmsConnection: Boolean = false,
    val pmsIp: String? = null,
    val pmsPort: Int = 0,
) {
    companion object {
        const val DEFAULT_HL7_VERSION = "2.5.1"
    }
}