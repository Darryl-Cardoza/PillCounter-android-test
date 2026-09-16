package com.rite.pillcounting.feature.login.di

import com.rite.pillcounting.core.room.dao.UserDao
import com.rite.pillcounting.feature.login.data.LoginRepository
import com.rite.pillcounting.feature.login.data.remote.ILoginApi
import com.rite.pillcounting.feature.login.domain.data.ILoginRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * Dagger Hilt module responsible for providing dependencies related to the
 * **User Login** feature of the application.
 *
 * This module defines how to construct and provide the [ILoginApi]
 * (Retrofit API service) and the [ILoginRepository] (data repository)
 * used for user authentication and session management.
 *
 * All dependencies defined here are scoped as singletons, ensuring that
 * the same instance is shared throughout the application's lifecycle.
 *
 * @see ILoginApi
 * @see ILoginRepository
 * @see LoginRepository
 */
@Module
@InstallIn(SingletonComponent::class)
object LoginModule {

    /**
     * Provides a singleton instance of the [ILoginApi].
     *
     * This API interfaceDetail defines the network endpoints for performing
     * user authentication operations such as login, token refresh,
     * and credential validation.
     *
     * @param retrofit The [Retrofit] instance used to create the API implementation.
     * @return A concrete implementation of [ILoginApi].
     */
    @Provides
    @Singleton
    fun provideLoginApi(
        retrofit: Retrofit
    ): ILoginApi =
        retrofit.create(ILoginApi::class.java)

    /**
     * Provides a singleton instance of the [ILoginRepository].
     *
     * The repository acts as the data management layer for user login,
     * bridging between the remote API ([ILoginApi]) and local persistence
     * ([UserDao]). It handles authentication logic, token storage,
     * and background threading for network requests.
     *
     * @param userDao The [UserDao] used to access and manage local user data.
     * @param loginApi The [ILoginApi] used to perform authentication API calls.
     * @param ioDispatcher The [CoroutineDispatcher] used for performing I/O-bound tasks.
     * @return A concrete implementation of [ILoginRepository].
     */
    @Provides
    @Singleton
    fun provideLoginRepository(
        userDao: UserDao,
        loginApi: ILoginApi,
        ioDispatcher: CoroutineDispatcher
    ): ILoginRepository =
        LoginRepository(
            userDao = userDao,
            loginApi = loginApi,
            ioDispatcher = ioDispatcher
        )
}
