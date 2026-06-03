package com.rite.pillcounting.core.refreshToken.di

import com.rite.pillcounting.BuildConfig
import com.rite.pillcounting.core.refreshToken.data.remote.IRefreshTokenAPI
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Provides a **dedicated lightweight networking stack** exclusively used for the
 * **token refresh API flow**, fully isolated from the main app network client.
 * @see IRefreshTokenAPI for API contract
 */
@Module
@InstallIn(SingletonComponent::class)
object RefreshTokenModule {
    /** Base URL for token refresh API calls. */
    //private const val BASE_URL = "https://pill.ccrlindia.com:8000/"

    // ─────────────────────────────── OkHttp Client ───────────────────────────────

    /**
     * Provides a minimal [OkHttpClient] dedicated for token refresh operations.
     *
     * - Includes basic [HttpLoggingInterceptor] for debugging purposes.
     * - Uses shorter timeouts (20 seconds) for quick refresh operations.
     *
     * @return Configured [OkHttpClient] instance for refresh token calls.
     */
    @Provides
    @Singleton
    @Named("refresh_okhttp")
    fun provideRefreshOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

    // ─────────────────────────────── Moshi Parser ───────────────────────────────

    /**
     * Provides a lightweight [Moshi] JSON parser for the refresh API.
     *
     * This instance is isolated from the main Moshi instance to ensure
     * modularity and reduce unintended coupling.
     *
     * @return A configured [Moshi] instance with [KotlinJsonAdapterFactory].
     */
    @Provides
    @Singleton
    @Named("refresh_moshi")
    fun provideRefreshMoshi(): Moshi =
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

    // ─────────────────────────────── Retrofit ───────────────────────────────

    /**
     * Provides a [Retrofit] instance for the token refresh API.
     *
     * Uses:
     * - The isolated `@Named("refresh_okhttp")` client.
     * - The isolated `@Named("refresh_moshi")` parser.
     *
     * @param okHttpClient Minimal OkHttp client for token refresh.
     * @param moshi JSON serialization/deserialization factory.
     * @return A fully configured [Retrofit] instance for refresh calls.
     */
    @Provides
    @Singleton
    @Named("refresh_retrofit")
    fun provideRefreshRetrofit(
        @Named("refresh_okhttp") okHttpClient: OkHttpClient,
        @Named("refresh_moshi") moshi: Moshi
    ): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    // ─────────────────────────────── API Service ───────────────────────────────

    /**
     * Provides the Retrofit implementation of [IRefreshTokenAPI].
     *
     * Uses the dedicated `@Named("refresh_retrofit")` Retrofit instance to
     * ensure the refresh mechanism is completely independent of the main API client.
     *
     * @param retrofit A Retrofit instance qualified with `@Named("refresh_retrofit")`.
     * @return Concrete implementation of [IRefreshTokenAPI].
     */
    @Provides
    @Singleton
    fun provideRefreshTokenApi(
        @Named("refresh_retrofit") retrofit: Retrofit
    ): IRefreshTokenAPI =
        retrofit.create(IRefreshTokenAPI::class.java)
}
