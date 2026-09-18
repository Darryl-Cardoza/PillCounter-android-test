package com.rite.pillcounting.feature.settings.di

import com.rite.pillcounting.feature.settings.data.ApplicationSettingsRepository
import com.rite.pillcounting.feature.settings.data.remote.IApplicationSettingInterface
import com.rite.pillcounting.feature.settings.domain.data.IApplicationSettingsRepository
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * Dagger Hilt module responsible for providing all dependencies related to
 * application settings and configuration management.
 *
 * This module defines how instances of the [IApplicationSettingInterface]
 * (Retrofit API service) and [IApplicationSettingsRepository] (data repository)
 * are constructed and shared across the app.
 *
 * @see IApplicationSettingInterface
 * @see IApplicationSettingsRepository
 * @see ApplicationSettingsRepository
 */
@Module
@InstallIn(SingletonComponent::class)
object ApplicationSettingsModule {

    /**
     * Provides a singleton instance of the [IApplicationSettingInterface].
     *
     * This interfaceDetail defines the Retrofit API endpoints related to application
     * settings such as configuration parameters, environment setup, and other
     * system-level metadata.
     *
     * @param retrofit The [retrofit2.Retrofit] instance used to create the API service.
     * @return The concrete implementation of [IApplicationSettingInterface].
     */
    @Provides
    @Singleton
    fun provideApplicationSettingApi(
        retrofit: Retrofit
    ): IApplicationSettingInterface =
        retrofit.create(IApplicationSettingInterface::class.java)

    /**
     * Provides a singleton instance of the [IApplicationSettingsRepository].
     *
     * The repository acts as an abstraction layer between the data sources
     * (remote API and local storage) and the domain layer, handling data flow
     * and persistence logic for application settings.
     *
     * @param apiService The [IApplicationSettingInterface] implementation for API calls.
     * @param preferenceHelper The [PreferenceHelper] instance used for caching and local storage.
     * @return A concrete implementation of [IApplicationSettingsRepository].
     */
    @Provides
    @Singleton
    fun provideApplicationSettingsRepository(
        apiService: IApplicationSettingInterface,
        preferenceHelper: PreferenceHelper
    ): IApplicationSettingsRepository =
        ApplicationSettingsRepository(
            apiService = apiService,
            preferenceHelper = preferenceHelper,
        )
}
