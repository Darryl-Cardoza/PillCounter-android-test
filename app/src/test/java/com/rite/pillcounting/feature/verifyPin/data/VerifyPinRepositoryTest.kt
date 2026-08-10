package com.rite.pillcounting.feature.verifyPin.data

import com.rite.pillcounting.core.utils.notification.FCMService
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
    private val fcmService: FCMService = mockk()
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var repository: VerifyPinRepository

    private val deviceKey = "device-key"
    private val appVersion = "1.0.0"
    private val fcmToken = "fcm-token"

    /** Mirrors the payload the repository composes — fcmToken and platform are fixed by it. */
    private fun expectedRequest(email: String, otp: String) = VerifyPinRequest(
        email = email,
        otp = otp,
        fcmToken = fcmToken,
        deviceKey = deviceKey,
        platform = "android",
        appVersion = appVersion
    )

    @Before
    fun setup() {
        coEvery { fcmService.getToken() } returns fcmToken
        repository = VerifyPinRepository(verifyPinApi, fcmService, testDispatcher)
    }

    // VP_REPO_001
    @Test
    fun `verifyPin returns success with VerifyPinResponse when API succeeds`() = runTest {
        val email = "user@pharmacy.com"
        val otp = "1234"
        val response = VerifyPinResponse(status = 200, isSuccess = true, message = "Verified")
        coEvery { verifyPinApi.verifyPin(expectedRequest(email, otp)) } returns response

        val result = repository.verifyPin(email, otp, deviceKey, appVersion)

        assertTrue(result.isSuccess)
        assertEquals(response, result.getOrNull())
    }

    // VP_REPO_002
    @Test
    fun `verifyPin returns failure when API throws HttpException`() = runTest {
        val exception = HttpException(Response.error<Any>(400, "".toResponseBody(null)))
        coEvery { verifyPinApi.verifyPin(any()) } throws exception

        val result = repository.verifyPin("user@pharmacy.com", "1234", deviceKey, appVersion)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is HttpException)
    }

    // VP_REPO_003
    @Test
    fun `verifyPin returns failure when API throws IOException`() = runTest {
        coEvery { verifyPinApi.verifyPin(any()) } throws IOException("connection refused")

        val result = repository.verifyPin("user@pharmacy.com", "1234", deviceKey, appVersion)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
    }

    // VP_REPO_004
    @Test
    fun `verifyPin passes correct email and otp in the request body`() = runTest {
        val email = "admin@rite.com"
        val otp = "5678"
        coEvery { verifyPinApi.verifyPin(any()) } returns VerifyPinResponse()

        repository.verifyPin(email, otp, deviceKey, appVersion)

        coVerify { verifyPinApi.verifyPin(expectedRequest(email, otp)) }
    }

    /**
     * The install identity travels with the credentials — it is what lets the backend bind a
     * terminal to this device, so a dropped device key would break terminal claiming.
     */
    // VP_REPO_005
    @Test
    fun `verifyPin passes device identity in the request body`() = runTest {
        coEvery { verifyPinApi.verifyPin(any()) } returns VerifyPinResponse()

        repository.verifyPin("admin@rite.com", "5678", deviceKey, appVersion)

        coVerify {
            verifyPinApi.verifyPin(
                match {
                    it.deviceKey == deviceKey &&
                        it.appVersion == appVersion &&
                        it.platform == "android"
                }
            )
        }
    }

    /** A failed token fetch shouldn't block verification — it just goes out with an empty token. */
    // VP_REPO_006
    @Test
    fun `verifyPin falls back to empty fcmToken when FCMService returns null`() = runTest {
        coEvery { fcmService.getToken() } returns null
        coEvery { verifyPinApi.verifyPin(any()) } returns VerifyPinResponse()

        repository.verifyPin("admin@rite.com", "5678", deviceKey, appVersion)

        coVerify { verifyPinApi.verifyPin(match { it.fcmToken == "" }) }
    }
}
