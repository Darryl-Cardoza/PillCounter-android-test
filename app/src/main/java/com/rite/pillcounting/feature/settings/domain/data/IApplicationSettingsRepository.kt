package com.rite.pillcounting.feature.settings.domain.data

import com.rite.pillcounting.core.models.ApiResponse
import com.rite.pillcounting.feature.settings.domain.model.SettingsDataDto

/**
 * Defines the contract for the Application Settings repository.
 * This abstraction allows for interchangeable data sources (e.g., network, local database)
 * and is crucial for unit testing the components that use it.
 */
interface IApplicationSettingsRepository {

    /**
     * Retrieves the application settings.
     *
     * @return An [SettingsDataDto] object containing the settings.
     * @throws Exception if the data fetching fails (e.g., network error, parsing error).
     */
    suspend fun getApplicationSettings(): ApiResponse<SettingsDataDto>
}
