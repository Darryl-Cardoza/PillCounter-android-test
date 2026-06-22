package com.rite.pillcounting.feature.settings.data

import android.util.Log
import com.rite.pillcounting.core.models.ApiResponse
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.settings.data.remote.IApplicationSettingInterface
import com.rite.pillcounting.feature.settings.domain.model.SettingsDataDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationSettingsRepositoryTest {

    private lateinit var apiService: IApplicationSettingInterface
    private lateinit var preferenceHelper: PreferenceHelper
    private lateinit var repository: ApplicationSettingsRepository

    @Before
    fun setup() {
        // AppLogger wraps android.util.Log, which is not available on the JVM.
        mockkStatic(Log::class)
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        apiService = mockk()
        preferenceHelper = mockk(relaxed = true)

        repository = ApplicationSettingsRepository(
            apiService = apiService,
            preferenceHelper = preferenceHelper
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun httpException(code: Int): HttpException {
        val body = "error".toResponseBody("text/plain".toMediaTypeOrNull())
        return HttpException(Response.error<Any>(code, body))
    }

    @Test
    fun `getApplicationSettings returns response from api`() = runTest {
        val expected = mockk<ApiResponse<SettingsDataDto>>()
        coEvery { apiService.getApplicationSettings(androidVersion = "android") } returns expected

        val result = repository.getApplicationSettings()

        assertSame(expected, result)
        coVerify(exactly = 1) { apiService.getApplicationSettings(androidVersion = "android") }
    }

    @Test
    fun `getApplicationSettings rethrows HttpException`() = runTest {
        val exception = httpException(500)
        coEvery { apiService.getApplicationSettings(androidVersion = "android") } throws exception

        val thrown = try {
            repository.getApplicationSettings()
            null
        } catch (e: HttpException) {
            e
        }
        assertSame(exception, thrown)
    }

    @Test
    fun `getApplicationSettings rethrows generic exception`() = runTest {
        val exception = RuntimeException("boom")
        coEvery { apiService.getApplicationSettings(androidVersion = "android") } throws exception

        val thrown = try {
            repository.getApplicationSettings()
            null
        } catch (e: RuntimeException) {
            e
        }
        assertSame(exception, thrown)
    }
}
