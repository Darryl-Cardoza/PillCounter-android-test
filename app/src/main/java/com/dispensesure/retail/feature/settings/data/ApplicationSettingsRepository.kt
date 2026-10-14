package com.dispensesure.retail.feature.settings.data

import com.dispensesure.retail.core.models.ApiResponse
import com.dispensesure.retail.feature.settings.data.remote.IApplicationSettingInterface
import com.dispensesure.retail.feature.settings.domain.data.IApplicationSettingsRepository
import com.dispensesure.retail.feature.settings.domain.model.SettingsDataDto
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import retrofit2.HttpException
import javax.inject.Inject

/**
 * Implementation of [IApplicationSettingsRepository] that retrieves settings via network.
 *
 * @property apiService Retrofit service for settings and auth.
 * @property preferenceHelper Manages local access and refresh tokens.
 */
class ApplicationSettingsRepository @Inject constructor(
    private val apiService: IApplicationSettingInterface,
    private val preferenceHelper: PreferenceHelper
) : IApplicationSettingsRepository {

    private val logger = AppLogger.Companion.create<ApplicationSettingsRepository>()

    override suspend fun getApplicationSettings(): ApiResponse<SettingsDataDto> {
        try {

            return apiService.getApplicationSettings(androidVersion = "android")
        } catch (e: HttpException) {
            logger.w("Http error when fetching settings", e, event = LogEvent.SETTINGS_FETCH_FAILED)
            throw e
        } catch (e: Exception) {
            logger.w("Unexpected error when fetching settings", e, event = LogEvent.SETTINGS_FETCH_FAILED)
            throw e
        }
    }
}
