package com.rite.pillcounting.feature.verifyPin.di

import com.rite.pillcounting.core.utils.notification.FCMService
import com.rite.pillcounting.feature.otp.data.VerifyPinRepository
import com.rite.pillcounting.feature.verifyPin.data.remote.IVerifyPinAPI
import com.rite.pillcounting.feature.verifyPin.domain.data.IVerifyPinRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * Dagger Hilt module that provides all dependencies required for
 * the **Verify Pin** feature of the application.
 *
 * This module defines how to create the Retrofit API service and the
 * corresponding repository implementation for verifying OTP or user PINs
 * during the registration or authentication process.
 *
 * The dependencies provided here are scoped as singletons to ensure that
 * one shared instance exists throughout the application's lifecycle.
 *
 * @see IVerifyPinAPI
 * @see IVerifyPinRepository
 * @see VerifyPinRepository
 */
@Module
@InstallIn(SingletonComponent::class)
object VerifyPinModule {

    /**
     * Provides a singleton instance of the [IVerifyPinAPI].
     *
     * This API interfaceDetail defines the endpoints required to verify
     * one-time PINs or OTPs as part of the user registration or
     * multi-factor authentication process.
     *
     * @param retrofit The [Retrofit] instance used to generate the API service.
     * @return The concrete implementation of [IVerifyPinAPI].
     */
    @Provides
    @Singleton
    fun provideVerifyPinApi(
        retrofit: Retrofit
    ): IVerifyPinAPI =
        retrofit.create(IVerifyPinAPI::class.java)

    /**
     * Provides a singleton instance of the [IVerifyPinRepository].
     *
     * The repository acts as an abstraction layer between the data layer
     * (network API) and the domain layer, managing the logic for sending
     * and verifying PIN codes asynchronously.
     *
     * @param verifyPinApi The [IVerifyPinAPI] instance for performing network operations.
     * @param fcmService Supplies this install's current FCM registration token.
     * @param ioDispatcher The [CoroutineDispatcher] used to handle I/O operations off the main thread.
     * @return A concrete implementation of [IVerifyPinRepository].
     */
    @Provides
    @Singleton
    fun provideVerifyPinRepository(
        verifyPinApi: IVerifyPinAPI,
        fcmService: FCMService,
        ioDispatcher: CoroutineDispatcher,
    ): IVerifyPinRepository =
        VerifyPinRepository(
            verifyPinApi = verifyPinApi,
            fcmService = fcmService,
            ioDispatcher = ioDispatcher,
        )
}
