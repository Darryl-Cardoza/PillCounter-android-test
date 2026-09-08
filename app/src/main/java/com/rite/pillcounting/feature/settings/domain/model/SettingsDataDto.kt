package com.rite.pillcounting.feature.settings.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Data Transfer Object (DTO) representing the full set of application settings
 * received from the backend API.
 *
 * This model encapsulates metadata like versioning and feature toggles
 * (e.g., maintenance mode), along with the actual theming and branding settings.
 *
 * @property isMaintenanceMode Flag indicating whether the app should operate in maintenance mode.
 * @property settings The actual application settings payload (e.g., theme colors, branding assets).
 */
@JsonClass(generateAdapter = true)
data class SettingsDataDto(
    @Json(name = "min_version") val minVersion: String?,
    @Json(name = "is_maintenance_mode") val isMaintenanceMode: Boolean,
    @Json(name = "settings") val settings: ApplicationSettingsResponse,
    @Json(name = "hl7_config") val hl7Config: ApplicationSettingsHL7Config?,  // nullable now
    /**
     * Backend-controlled maximum time (seconds) the user is allowed to operate offline
     * before being forced back to Login. Nullable so older backends still deserialize;
     * `SessionHealthController` falls back to the persisted default when this is null.
     */
    @Json(name = "offline_session_threshold_seconds") val offlineSessionThresholdSeconds: Long? = null
)
