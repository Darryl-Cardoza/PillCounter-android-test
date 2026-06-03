package com.rite.pillcounting.feature.profile.di

import com.rite.pillcounting.core.room.dao.UserDao
import com.rite.pillcounting.feature.settings.data.remote.IApplicationSettingInterface
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.profile.data.ProfileRepository
import com.rite.pillcounting.feature.profile.data.remote.IProfileApi
import com.rite.pillcounting.feature.profile.domain.data.IProfileRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * Dagger Hilt module that provides all dependencies required for the
 * **User Profile** feature of the application.
 *
 * This module defines how to construct and provide instances of the
 * [IProfileApi] (Retrofit API service) and [IProfileRepository]
 * (repository layer). These dependencies are responsible for fetching,
 * updating, and synchronizing user profile information between the
 * local database and remote API.
 *
 * All dependencies are provided as singletons, ensuring one shared
 * instance across the application lifecycle.
 *
 * @see IProfileApi
 * @see IProfileRepository
 * @see ProfileRepository
 */
@Module
@InstallIn(SingletonComponent::class)
object ProfileModule {

    /**
     * Provides a singleton instance of the [IProfileApi].
     *
     * This interfaceDetail defines the Retrofit endpoints responsible for user
     * profile operations, such as fetching profile data, updating user
     * information, and managing account details.
     *
     * @param retrofit The [Retrofit] instance used to generate the API implementation.
     * @return The concrete implementation of [IProfileApi].
     */
    @Provides
    @Singleton
    fun provideProfileApi(
        retrofit: Retrofit
    ): IProfileApi =
        retrofit.create(IProfileApi::class.java)

    /**
     * Provides a singleton instance of the [IProfileRepository].
     *
     * The repository serves as a bridge between the network layer
     * ([IProfileApi]), local persistence ([UserDao]), and preferences
     * ([PreferenceHelper]). It encapsulates all profile-related business
     * logic and data flow, ensuring separation of concerns and testability.
     *
     * @param api The [IProfileApi] used for remote API interactions.
     * @param userDao The [UserDao] used for local profile caching and database access.
     * @param preferenceHelper The [PreferenceHelper] used for user-specific settings and preferences.
     * @param applicationSettingApi The [IApplicationSettingInterface] used for retrieving configuration data.
     * @param ioDispatcher The [CoroutineDispatcher] used for performing I/O-bound operations on background threads.
     * @return A concrete implementation of [IProfileRepository].
     */
    @Provides
    @Singleton
    fun provideProfileRepository(
        api: IProfileApi,
        userDao: UserDao,
        preferenceHelper: PreferenceHelper,
        applicationSettingApi: IApplicationSettingInterface,
        ioDispatcher: CoroutineDispatcher
    ): IProfileRepository =
        ProfileRepository(
            profileApi = api,
            ioDispatcher = ioDispatcher,
            preferenceHelper = preferenceHelper,
            applicationSettingApi = applicationSettingApi
        )
}
