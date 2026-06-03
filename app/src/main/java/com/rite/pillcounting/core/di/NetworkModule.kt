package com.rite.pillcounting.core.di

import android.content.Context
import com.chuckerteam.chucker.api.ChuckerInterceptor
import com.rite.pillcounting.BuildConfig
import com.rite.pillcounting.core.api.interfaceDetail.HeaderInterceptor
import com.rite.pillcounting.core.refreshToken.data.TokenAuthenticator
import com.rite.pillcounting.core.security.RuntimeUnit
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
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

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

    /** Provides an HTTP logger for debugging API traffic. */
    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
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
        runtimeUnit: RuntimeUnit
    ): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(HeaderInterceptor(runtimeUnit))
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
