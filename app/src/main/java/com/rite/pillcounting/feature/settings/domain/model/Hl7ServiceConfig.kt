package com.rite.pillcounting.feature.settings.domain.model

/**
 * Static mDNS service type constants for HL7 local network discovery.
 * These are protocol-level values that do not vary per pharmacy.
 */
object Hl7ServiceConfig {
    const val PMS_HOST_NAME = "_ritepmsserver._tcp"
    const val PILL_COUNTER_HOST_NAME = "_pillcounting._tcp"
}
