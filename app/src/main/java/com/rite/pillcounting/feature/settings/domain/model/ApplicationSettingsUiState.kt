package com.rite.pillcounting.feature.settings.domain.model

/**
 * Represents the UI state for the Application Settings screen.
 *
 * This state is observed by the UI to react to data changes such as loading status,
 * error messages, theming settings, branding, and special flags like
 * maintenance mode or update requirements.
 *
 * @property isLoading Indicates whether the settings are currently being loaded.
 * @property errorMessage A nullable error message to display in the UI if loading fails.
 * @property colorSettings The color settings for light and dark themes.
 * @property appLogoUrl The URL or identifier for the application logo to be displayed.
 * @property appSettings The full application settings DTO returned from backend.
 * @property isMaintenanceMode Flag that indicates whether the app should be put in maintenance mode.
 * @property isUpdateRequired Flag that indicates whether the app should force the user to update
 *                            based on remote version vs current installed version.
 */
data class ApplicationSettingsUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val colorSettings: ColorSettings? = null,
    val appLogoUrl: String? = null,
    val appSettings: SettingsDataDto? = null,
    val isMaintenanceMode: Boolean = false,
    val isUpdateRequired: Boolean = false,


    val isHl7Enabled: Boolean? = null,
    val nsdBroadcastType: String? = null,
    val nsdDiscoveryType: String? = null
)
