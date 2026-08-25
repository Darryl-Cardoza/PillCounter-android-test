package com.rite.pillcounting.core.health.di

import android.content.Context
import com.chuckerteam.chucker.api.ChuckerInterceptor
import com.rite.pillcounting.BuildConfig
import com.rite.pillcounting.core.api.interfaceDetail.HeaderInterceptor
import com.rite.pillcounting.core.health.data.HealthRepository
import com.rite.pillcounting.core.health.data.remote.IHealthApi
import com.rite.pillcounting.core.health.domain.data.IHealthRepository
import com.rite.pillcounting.core.security.RuntimeUnit
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/**
 * Isolated networking stack for the `/health` endpoint.
 *
 * Description:
 * Mirrors the pattern used by `RefreshTokenModule` — dedicated OkHttp / Moshi
 * / Retrofit instances that are NOT reused by the main app stack. This isolation
 * is deliberate: the main stack carries the `HealthGateInterceptor`, which
 * short-circuits every non-allowlisted request whenever the app is offline; if
 * `/health` itself went through that interceptor, the recovery path would be
 * blocked and the app could never come back online.
 */
@Module
@InstallIn(SingletonComponent::class)
object HealthModule {

    /**
     * Provides an OkHttp client dedicated to the `/health` endpoint.
     *
     * What it does:
     * - Attaches only the [HeaderInterceptor] so `X-Server-Key` and `Content-Type` are sent.
     * - Attaches a plain body-logging interceptor for parity with the refresh stack.
     * - Uses tight 15s timeouts so a stuck server does not delay foreground checks.
     *
     * @param runtimeUnit Source of the server key material.
     * @return Configured [OkHttpClient] instance for `/health` calls.
     */
    @Provides
    @Singleton
    @Named("health_okhttp")
    fun provideHealthOkHttpClient(
        runtimeUnit: RuntimeUnit,
        @ApplicationContext context: Context
    ): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(HeaderInterceptor(runtimeUnit))
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                else HttpLoggingInterceptor.Level.NONE
            })
            // Attach Chucker so /health traffic shows up alongside main-stack calls
            // in the in-app debug inspector. Mirrors the main NetworkModule wiring.
            .addInterceptor(ChuckerInterceptor(context))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

    /**
     * Provides a Moshi instance dedicated to the `/health` payloads.
     *
     * @return Standalone [Moshi] configured with Kotlin reflection.
     */
    @Provides
    @Singleton
    @Named("health_moshi")
    fun provideHealthMoshi(): Moshi =
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

    /**
     * Provides the Retrofit instance for the `/health` endpoint.
     *
     * @param okHttpClient The isolated OkHttp client from [provideHealthOkHttpClient].
     * @param moshi The isolated Moshi instance from [provideHealthMoshi].
     * @return Configured [Retrofit] instance ready for creating [IHealthApi].
     */
    @Provides
    @Singleton
    @Named("health_retrofit")
    fun provideHealthRetrofit(
        @Named("health_okhttp") okHttpClient: OkHttpClient,
        @Named("health_moshi") moshi: Moshi
    ): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    /**
     * Provides the Retrofit implementation of [IHealthApi] using the isolated stack.
     *
     * @param retrofit The named `health_retrofit` instance.
     * @return Retrofit-generated implementation of [IHealthApi].
     */
    @Provides
    @Singleton
    fun provideHealthApi(
        @Named("health_retrofit") retrofit: Retrofit
    ): IHealthApi =
        retrofit.create(IHealthApi::class.java)
}

/**
 * Binds [HealthRepository] to the [IHealthRepository] contract.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class HealthRepositoryModule {

    /**
     * Binds the concrete [HealthRepository] to the [IHealthRepository] contract.
     *
     * @param impl The concrete repository implementation.
     * @return The bound [IHealthRepository] singleton.
     */
    @Binds
    @Singleton
    abstract fun bindHealthRepository(impl: HealthRepository): IHealthRepository
}
