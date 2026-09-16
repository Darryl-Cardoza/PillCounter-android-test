package com.rite.pillcounting.feature.login.data

import com.rite.pillcounting.core.room.dao.UserDao
import com.rite.pillcounting.feature.login.data.remote.ILoginApi
import com.rite.pillcounting.feature.login.domain.model.LoginRequest
import com.rite.pillcounting.feature.login.domain.model.LoginResponse
import com.rite.pillcounting.feature.login.domain.model.LogoutRequest
import com.rite.pillcounting.feature.login.domain.model.LogoutResponse
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
class LoginRepositoryTest {

    private val loginApi: ILoginApi = mockk()
    private val userDao: UserDao = mockk(relaxed = true)
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var repository: LoginRepository

    @Before
    fun setup() {
        repository = LoginRepository(userDao, loginApi, testDispatcher)
    }

    // -------------------------------------------------------------------------
    // login()
    // -------------------------------------------------------------------------

    // LOG_REPO_001
    @Test
    fun `login returns success with LoginResponse when API call succeeds`() = runTest {
        val email = "user@pharmacy.com"
        val response = LoginResponse(status = 200, isSuccess = true, message = "OTP sent")
        coEvery { loginApi.login(LoginRequest(email = email)) } returns response

        val result = repository.login(email)

        assertTrue(result.isSuccess)
        assertEquals(response, result.getOrNull())
    }

    // LOG_REPO_002
    @Test
    fun `login returns failure when API throws HttpException`() = runTest {
        val email = "user@pharmacy.com"
        val exception = HttpException(Response.error<Any>(401, "".toResponseBody(null)))
        coEvery { loginApi.login(any()) } throws exception

        val result = repository.login(email)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is HttpException)
    }

    // LOG_REPO_003
    @Test
    fun `login returns failure when API throws IOException`() = runTest {
        coEvery { loginApi.login(any()) } throws IOException("timeout")

        val result = repository.login("user@pharmacy.com")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
    }

    // LOG_REPO_004
    @Test
    fun `login wraps the correct email in LoginRequest sent to API`() = runTest {
        val email = "admin@rite.com"
        coEvery { loginApi.login(any()) } returns LoginResponse()

        repository.login(email)

        coVerify { loginApi.login(LoginRequest(email = email)) }
    }

    // -------------------------------------------------------------------------
    // logout()
    // -------------------------------------------------------------------------

    // LOG_REPO_005
    @Test
    fun `logout returns success with LogoutResponse when API call succeeds`() = runTest {
        val token = "refresh_abc"
        val response = LogoutResponse(status = 200, isSuccess = true)
        coEvery { loginApi.logout(LogoutRequest(refreshToken = token)) } returns response

        val result = repository.logout(token)

        assertTrue(result.isSuccess)
        assertEquals(response, result.getOrNull())
    }

    // LOG_REPO_006
    @Test
    fun `logout returns failure when API throws exception`() = runTest {
        coEvery { loginApi.logout(any()) } throws IOException("network error")

        val result = repository.logout("token")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
    }
}
