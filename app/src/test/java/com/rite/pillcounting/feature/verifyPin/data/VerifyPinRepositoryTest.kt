package com.rite.pillcounting.feature.verifyPin.data

import com.rite.pillcounting.feature.otp.data.VerifyPinRepository
import com.rite.pillcounting.feature.verifyPin.data.remote.IVerifyPinAPI
import com.rite.pillcounting.feature.verifyPin.domain.model.VerifyPinRequest
import com.rite.pillcounting.feature.verifyPin.domain.model.VerifyPinResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class VerifyPinRepositoryTest {

    private val verifyPinApi: IVerifyPinAPI = mockk()
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var repository: VerifyPinRepository

    @Before
    fun setup() {
        repository = VerifyPinRepository(verifyPinApi, testDispatcher)
    }

    // VP_REPO_001
    @Test
    fun `verifyPin returns success with VerifyPinResponse when API succeeds`() = runTest {
        val email = "user@pharmacy.com"
        val otp = "1234"
        val response = VerifyPinResponse(status = 200, isSuccess = true, message = "Verified")
        coEvery { verifyPinApi.verifyPin(VerifyPinRequest(email, otp)) } returns response

        val result = repository.verifyPin(email, otp)

        assertTrue(result.isSuccess)
        assertEquals(response, result.getOrNull())
    }

    // VP_REPO_002
    @Test
    fun `verifyPin returns failure when API throws HttpException`() = runTest {
        val exception = HttpException(Response.error<Any>(400, "".toResponseBody(null)))
        coEvery { verifyPinApi.verifyPin(any()) } throws exception

        val result = repository.verifyPin("user@pharmacy.com", "1234")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is HttpException)
    }

    // VP_REPO_003
    @Test
    fun `verifyPin returns failure when API throws IOException`() = runTest {
        coEvery { verifyPinApi.verifyPin(any()) } throws IOException("connection refused")

        val result = repository.verifyPin("user@pharmacy.com", "1234")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
    }

    // VP_REPO_004
    @Test
    fun `verifyPin passes correct email and otp in the request body`() = runTest {
        val email = "admin@rite.com"
        val otp = "5678"
        coEvery { verifyPinApi.verifyPin(any()) } returns VerifyPinResponse()

        repository.verifyPin(email, otp)

        coVerify { verifyPinApi.verifyPin(VerifyPinRequest(email = email, otp = otp)) }
    }
}
