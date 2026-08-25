package com.rite.pillcounting.core.health.data

import android.util.Log
import com.rite.pillcounting.core.health.data.remote.IHealthApi
import com.rite.pillcounting.core.health.data.remote.dto.HealthCheckData
import com.rite.pillcounting.core.models.ApiResponse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * Unit tests for [HealthRepository].
 *
 * Covers: success mapping, non-2xx failure, empty body, is_healthy=false, and IOException.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HealthRepositoryTest {

    private lateinit var api: IHealthApi
    private lateinit var repository: HealthRepository

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        api = mockk()
        repository = HealthRepository(api, UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun checkHealth_success_returnsSuccessWithHealthCheckData() = runTest {
        val data = HealthCheckData(isHealthy = true, checks = null, checkedAt = "2026-08-24T12:00:00Z")
        coEvery { api.getHealth() } returns Response.success(
            ApiResponse(status = 200, isSuccess = true, message = "ok", token = null, data = data)
        )

        val result = repository.checkHealth()

        assertTrue(result.isSuccess)
        assertEquals(data, result.getOrNull())
    }

    @Test
    fun checkHealth_nonSuccessResponse_returnsFailure() = runTest {
        coEvery { api.getHealth() } returns Response.error(
            500,
            "".toResponseBody("application/json".toMediaTypeOrNull())
        )

        val result = repository.checkHealth()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("HTTP 500") == true)
    }

    @Test
    fun checkHealth_emptyBody_returnsFailure() = runTest {
        coEvery { api.getHealth() } returns Response.success(
            ApiResponse<HealthCheckData>(status = 200, isSuccess = true, message = "ok", token = null, data = null)
        )

        val result = repository.checkHealth()

        assertTrue(result.isFailure)
    }

    @Test
    fun checkHealth_isHealthyFalse_returnsFailure() = runTest {
        val data = HealthCheckData(isHealthy = false, checks = null, checkedAt = "2026-08-24T12:00:00Z")
        coEvery { api.getHealth() } returns Response.success(
            ApiResponse(status = 200, isSuccess = true, message = "ok", token = null, data = data)
        )

        val result = repository.checkHealth()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("is_healthy=false") == true)
    }

    @Test
    fun checkHealth_ioException_returnsFailure() = runTest {
        coEvery { api.getHealth() } throws IOException("no network")

        val result = repository.checkHealth()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
    }
}
