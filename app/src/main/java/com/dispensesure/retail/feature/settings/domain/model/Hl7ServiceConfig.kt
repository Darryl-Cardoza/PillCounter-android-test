package com.dispensesure.retail.feature.settings.domain.model

/**
 * Default mDNS service types for HL7 local network discovery.
 * The settings API can override either one per pharmacy; these are the fallbacks
 * used when it does not.
 */
object Hl7ServiceConfig {
    const val PMS_HOST_NAME = "_ritepmsserver._tcp"
    const val PILL_COUNTER_HOST_NAME = "_pillcounting._tcp"

    /**
     * mDNS service type: an underscore-prefixed label of 1-15 letters, digits or hyphens
     * followed by ._tcp. RFC 6763 caps the label at 15 and older NsdManager builds throw
     * on longer ones; MLLP is TCP only, so a _udp type could never reach the PMS.
     */
    private val SERVICE_TYPE = Regex("^_[A-Za-z0-9-]{1,15}\\._tcp$")

    /** Server value wins when it is a usable service type; anything else falls back. */
    fun resolvePmsHostName(fromServer: String?): String =
        fromServer?.takeIf { SERVICE_TYPE.matches(it) } ?: PMS_HOST_NAME

    /** Server value wins when it is a usable service type; anything else falls back. */
    fun resolvePillCounterHostName(fromServer: String?): String =
        fromServer?.takeIf { SERVICE_TYPE.matches(it) } ?: PILL_COUNTER_HOST_NAME
}
