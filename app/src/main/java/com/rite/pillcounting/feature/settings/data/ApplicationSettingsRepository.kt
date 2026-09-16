package com.rite.pillcounting.feature.settings.data

import com.rite.pillcounting.core.models.ApiResponse
import com.rite.pillcounting.feature.settings.data.remote.IApplicationSettingInterface
import com.rite.pillcounting.feature.settings.domain.data.IApplicationSettingsRepository
import com.rite.pillcounting.feature.settings.domain.model.SettingsDataDto
import com.rite.pillcounting.core.utils.logger.AppLogger
import retrofit2.HttpException
import javax.inject.Inject

/**
 * Implementation of [IApplicationSettingsRepository] that retrieves settings via network.
 *
 * @property apiService Retrofit service for settings and auth.
 */
class ApplicationSettingsRepository @Inject constructor(
    private val apiService: IApplicationSettingInterface
) : IApplicationSettingsRepository {

    private val logger = AppLogger.Companion.create<ApplicationSettingsRepository>()

    override suspend fun getApplicationSettings(): ApiResponse<SettingsDataDto> {
        try {

            return apiService.getApplicationSettings(androidVersion = "android")
        } catch (e: HttpException) {
            logger.e("Http error when fetching settings", e)
            throw e
        } catch (e: Exception) {
            logger.e("Unexpected error when fetching settings", e)
            throw e
        }
    }
}
