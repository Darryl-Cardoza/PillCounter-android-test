package com.rite.pillcounting.core.di

import android.content.Context
import android.util.Log
import com.chuckerteam.chucker.api.ChuckerInterceptor
import com.rite.pillcounting.BuildConfig
import com.rite.pillcounting.core.api.interfaceDetail.HeaderInterceptor
import com.rite.pillcounting.core.health.logic.HealthGateInterceptor
import com.rite.pillcounting.core.refreshToken.data.TokenAuthenticator
import com.rite.pillcounting.core.security.RuntimeUnit
import com.rite.pillcounting.feature.dashboard.domain.model.TerminalUpdateRequestAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
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
import javax.inject.Singleton


/**
 * **NetworkModule**
 *
 * Provides the main network stack for authenticated API requests.
 *
 * Includes:
 * - [OkHttpClient] configured with authentication, interceptors, and timeouts
 * - [Retrofit] instance for core API calls
 * - [Moshi] JSON serialization with Kotlin support
 *
 * ---
 * ### ⚙️ Design Highlights
 * - Uses [TokenAuthenticator] to refresh access tokens automatically on `401 Unauthorized`.
 * - Adds [HeaderInterceptor] for dynamic headers (e.g., `Authorization`).
 * - Integrates [ChuckerInterceptor] for debugging traffic during development.
 * - Applies safe production-grade network timeouts.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /** Base URL for main backend API calls. */
    //private const val MAIN_API_BASE_URL = "https://pill.ccrlindia.com:8000/"
    //private const val MAIN_API_BASE_URL = "http://192.168.0.78:8000/"

    /** Matches `"key_material":"<value>"` (any quoted value) so it can be redacted from logs. */
    private val KEY_MATERIAL_REGEX = Regex("\"key_material\"\\s*:\\s*\"[^\"]*\"")

    /** Provides an HTTP logger for debugging API traffic, with KEK key material redacted. */
    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor { message ->
            Log.d(
                "OkHttp",
                KEY_MATERIAL_REGEX.replace(message, "\"key_material\":\"***REDACTED***\"")
            )
        }.apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE  // Silence in production
            }
        }

    @Provides
    @Singleton
    fun provideRuntimeUnit(
        @ApplicationContext context: Context
    ): RuntimeUnit = RuntimeUnit(context)

    /**
     * Provides the main [OkHttpClient] with:
     * - Authorization headers
     * - Logging
     * - Automatic token refresh
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(
        loggingInterceptor: HttpLoggingInterceptor,
        @ApplicationContext context: Context,
        tokenAuthenticator: TokenAuthenticator,
        runtimeUnit: RuntimeUnit,
        healthGateInterceptor: HealthGateInterceptor
    ): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(HeaderInterceptor(runtimeUnit))
            // Gate every non-allowlisted authed request while the app is offline. Attached
            // AFTER HeaderInterceptor so X-Server-Key is present on the (rare) allowlisted
            // requests that still go through, and BEFORE the authenticator so a synthetic
            // offline 599 never triggers a spurious token refresh.
            .addInterceptor(healthGateInterceptor)
            .addInterceptor(loggingInterceptor)
            .addInterceptor(ChuckerInterceptor(context))
            .authenticator(tokenAuthenticator)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    /** Provides the [Moshi] serializer with Kotlin support. */
    @Provides
    @Singleton
    fun provideMoshi(): Moshi =
        Moshi.Builder()
            .add(TerminalUpdateRequestAdapter())
            .add(KotlinJsonAdapterFactory())
            .build()

    /** Provides the primary Retrofit instance used for all app APIs. */
    @Provides
    @Singleton
    fun provideMainRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
}
