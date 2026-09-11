package com.rite.pillcounting.feature.settings.domain.model

/**
 * Default mDNS service types for HL7 local network discovery.
 * The settings API can override either one per pharmacy; these are the fallbacks
 * used when it does not.
 */
object Hl7ServiceConfig {
    const val PMS_HOST_NAME = "_ritepmsserver._tcp"
    const val PILL_COUNTER_HOST_NAME = "_pillcounting._tcp"

    /** Server value wins. Null or blank falls back to the constant; the format is not validated. */
    fun resolvePmsHostName(fromServer: String?): String =
        fromServer?.takeIf { it.isNotBlank() } ?: PMS_HOST_NAME

    /** Server value wins. Null or blank falls back to the constant; the format is not validated. */
    fun resolvePillCounterHostName(fromServer: String?): String =
        fromServer?.takeIf { it.isNotBlank() } ?: PILL_COUNTER_HOST_NAME
}
