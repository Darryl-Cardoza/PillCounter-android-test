package com.rite.pillcounting.core.refreshToken.di

import com.rite.pillcounting.core.refreshToken.data.remote.IRefreshTokenAPI
import com.squareup.moshi.Moshi
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Test
import retrofit2.Retrofit

/**
 * Unit tests for [RefreshTokenModule]'s `@Provides` functions.
 *
 * All providers construct pure JVM objects (OkHttp / Moshi / Retrofit) or delegate to
 * `retrofit.create(...)`, so each can be exercised directly on the JVM.
 *
 * SKIPPED: none.
 */
class RefreshTokenModuleTest {

    @Test
    fun `provideRefreshOkHttpClient returns non-null OkHttpClient`() {
        val client: OkHttpClient = RefreshTokenModule.provideRefreshOkHttpClient()

        assertNotNull(client)
    }

    @Test
    fun `provideRefreshMoshi returns non-null Moshi`() {
        val moshi: Moshi = RefreshTokenModule.provideRefreshMoshi()

        assertNotNull(moshi)
    }

    @Test
    fun `provideRefreshRetrofit returns non-null Retrofit`() {
        val okHttpClient = mockk<OkHttpClient>(relaxed = true)
        val moshi = RefreshTokenModule.provideRefreshMoshi()

        val retrofit: Retrofit = RefreshTokenModule.provideRefreshRetrofit(okHttpClient, moshi)

        assertNotNull(retrofit)
    }

    @Test
    fun `provideRefreshTokenApi returns non-null IRefreshTokenAPI`() {
        val retrofit = mockk<Retrofit>()
        every { retrofit.create(IRefreshTokenAPI::class.java) } returns
            mockk<IRefreshTokenAPI>(relaxed = true)

        val api: IRefreshTokenAPI = RefreshTokenModule.provideRefreshTokenApi(retrofit)

        assertNotNull(api)
    }
}
