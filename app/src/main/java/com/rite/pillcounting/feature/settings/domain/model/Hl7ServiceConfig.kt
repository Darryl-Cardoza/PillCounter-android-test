package com.rite.pillcounting.feature.settings.domain.model

/**
 * Default mDNS service types for HL7 local network discovery.
 * The settings API can override either one per pharmacy; these are the fallbacks
 * used when it does not.
 */
object Hl7ServiceConfig {
    const val PMS_HOST_NAME = "_ritepmsserver._tcp"
    const val PILL_COUNTER_HOST_NAME = "_pillcounting._tcp"

    /**
     * mDNS service type: an underscore-prefixed label followed by ._tcp or ._udp.
     * Anything else is rejected — NsdManager throws on it, and the value also reaches MSH-5.
     */
    private val SERVICE_TYPE = Regex("^_[A-Za-z0-9_-]{1,61}\\._(tcp|udp)$")

    /** Server value wins when it is a usable service type; anything else falls back. */
    fun resolvePmsHostName(fromServer: String?): String =
        fromServer?.takeIf { SERVICE_TYPE.matches(it) } ?: PMS_HOST_NAME

    /** Server value wins when it is a usable service type; anything else falls back. */
    fun resolvePillCounterHostName(fromServer: String?): String =
        fromServer?.takeIf { SERVICE_TYPE.matches(it) } ?: PILL_COUNTER_HOST_NAME
}
