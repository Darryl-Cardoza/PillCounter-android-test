package com.rite.pillcounting.feature.verifyPin.di

import com.rite.pillcounting.feature.otp.data.VerifyPinRepository
import com.rite.pillcounting.feature.verifyPin.data.remote.IVerifyPinAPI
import com.rite.pillcounting.feature.verifyPin.domain.data.IVerifyPinRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit

/**
 * Unit tests for [VerifyPinModule]'s `@Provides` functions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VerifyPinModuleTest {

    @Test
    fun `provideVerifyPinApi returns non-null IVerifyPinAPI`() {
        val retrofit = mockk<Retrofit>()
        every {
            retrofit.create(IVerifyPinAPI::class.java)
        } returns mockk<IVerifyPinAPI>(relaxed = true)

        val api = VerifyPinModule.provideVerifyPinApi(retrofit)

        assertNotNull(api)
    }

    @Test
    fun `provideVerifyPinRepository returns non-null VerifyPinRepository`() {
        val verifyPinApi = mockk<IVerifyPinAPI>(relaxed = true)
        val ioDispatcher = UnconfinedTestDispatcher()

        val repository: IVerifyPinRepository = VerifyPinModule.provideVerifyPinRepository(
            verifyPinApi = verifyPinApi,
            ioDispatcher = ioDispatcher
        )

        assertNotNull(repository)
        assertTrue(repository is VerifyPinRepository)
    }
}
