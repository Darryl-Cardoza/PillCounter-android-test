package com.rite.pillcounting.feature.settings.domain.data

import com.rite.pillcounting.feature.settings.domain.model.ApplicationSettingsUiState
import kotlinx.coroutines.flow.StateFlow

/**
 * Defines the contract for the ApplicationSettingsViewModel.
 * This interfaceDetail exposes the UI state and the actions that can be performed from the UI.
 */
interface IApplicationSettingsViewModel {

    /**
     * A reactive stream of the current UI state for the settings screen.
     */
    val uiState: StateFlow<ApplicationSettingsUiState>

    /**
     * Initiates a request to fetch the application settings.
     */
    fun fetchApplicationSettings()
}
