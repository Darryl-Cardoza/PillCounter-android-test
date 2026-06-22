package com.rite.pillcounting.core.di

import com.squareup.moshi.Moshi
import io.mockk.mockk
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assert.assertNotNull
import org.junit.Test
import retrofit2.Retrofit

/**
 * Unit tests for [NetworkModule]'s `@Provides` functions.
 *
 * Providers that construct only pure JVM objects (no Android deps) are invoked
 * directly with mocked arguments and asserted non-null.
 *
 * SKIPPED:
 * - `provideRuntimeUnit` — constructs `RuntimeUnit(context)` which needs a real Android
 *   [android.content.Context] (uses Android Keystore / EncryptedSharedPreferences).
 * - `provideOkHttpClient` — constructs `ChuckerInterceptor(context)`, requiring a real
 *   Android Context, so it cannot be built on the JVM.
 */
class NetworkModuleTest {

    @Test
    fun `provideLoggingInterceptor returns non-null HttpLoggingInterceptor`() {
        val interceptor: HttpLoggingInterceptor = NetworkModule.provideLoggingInterceptor()

        assertNotNull(interceptor)
    }

    @Test
    fun `provideMoshi returns non-null Moshi`() {
        val moshi: Moshi = NetworkModule.provideMoshi()

        assertNotNull(moshi)
    }

    @Test
    fun `provideMainRetrofit returns non-null Retrofit`() {
        val okHttpClient = mockk<OkHttpClient>(relaxed = true)
        val moshi = NetworkModule.provideMoshi()

        val retrofit: Retrofit = NetworkModule.provideMainRetrofit(okHttpClient, moshi)

        assertNotNull(retrofit)
    }
}
