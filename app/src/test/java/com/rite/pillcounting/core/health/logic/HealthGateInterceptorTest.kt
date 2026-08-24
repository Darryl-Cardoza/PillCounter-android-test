package com.rite.pillcounting.core.health.logic

import android.util.Log
import com.rite.pillcounting.core.health.domain.model.HealthState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for [HealthGateInterceptor].
 *
 * Covers: passthrough when HEALTHY/UNKNOWN, gate synthesizing 599 when OFFLINE/EXPIRED,
 * allowlisted endpoints always pass, and classifyAndReact routing on 5xx / IOException.
 */
class HealthGateInterceptorTest {

    private lateinit var controller: SessionHealthController
    private lateinit var stateFlow: MutableStateFlow<HealthState>
    private lateinit var interceptor: HealthGateInterceptor

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        controller = mockk(relaxed = true)
        stateFlow = MutableStateFlow(HealthState.HEALTHY)
        every { controller.state } returns stateFlow
        interceptor = HealthGateInterceptor(controller)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun chainFor(path: String, proceedResponse: Response? = null, proceedThrows: Throwable? = null): Interceptor.Chain {
        val request = Request.Builder().url("https://api.example.com$path").build()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        val slot = slot<Request>()
        if (proceedThrows != null) {
            every { chain.proceed(capture(slot)) } throws proceedThrows
        } else {
            every { chain.proceed(capture(slot)) } returns (proceedResponse ?: successResponse(request))
        }
        return chain
    }

    private fun successResponse(request: Request, code: Int = 200): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("ok")
            .body("".toResponseBody(null))
            .build()

    @Test
    fun healthy_passesRequestThrough() {
        stateFlow.value = HealthState.HEALTHY
        val chain = chainFor("/api/anything")

        val response = interceptor.intercept(chain)

        assertEquals(200, response.code)
    }

    @Test
    fun unknown_passesRequestThrough() {
        stateFlow.value = HealthState.UNKNOWN
        val chain = chainFor("/api/anything")

        val response = interceptor.intercept(chain)

        assertEquals(200, response.code)
    }

    @Test
    fun offline_shortCircuitsWith599_forNonAllowlistedRequest() {
        stateFlow.value = HealthState.OFFLINE
        val chain = chainFor("/api/anything")

        val response = interceptor.intercept(chain)

        assertEquals(599, response.code)
        assertEquals("Offline", response.message)
    }

    @Test
    fun expired_shortCircuitsWith599_forNonAllowlistedRequest() {
        stateFlow.value = HealthState.EXPIRED
        val chain = chainFor("/api/anything")

        val response = interceptor.intercept(chain)

        assertEquals(599, response.code)
    }

    @Test
    fun offline_allowsHealthEndpoint() {
        stateFlow.value = HealthState.OFFLINE
        val chain = chainFor("/health")

        val response = interceptor.intercept(chain)

        assertEquals(200, response.code)
    }

    @Test
    fun offline_allowsRefreshEndpoint() {
        stateFlow.value = HealthState.OFFLINE
        val chain = chainFor("/auth/refresh")

        val response = interceptor.intercept(chain)

        assertEquals(200, response.code)
    }

    @Test
    fun offline_allowsMobileSettingsEndpoint() {
        stateFlow.value = HealthState.OFFLINE
        val chain = chainFor("/mobile/get/settings")

        val response = interceptor.intercept(chain)

        assertEquals(200, response.code)
    }

    @Test
    fun healthy_5xxResponseTriggersClassifyAndReact() {
        stateFlow.value = HealthState.HEALTHY
        val request = Request.Builder().url("https://api.example.com/api/foo").build()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(any()) } returns successResponse(request, code = 502)

        val response = interceptor.intercept(chain)

        assertEquals(502, response.code)
        verify { controller.classifyAndReact(null, 502) }
    }

    @Test
    fun healthy_ioExceptionTriggersClassifyAndReact_andRethrows() {
        stateFlow.value = HealthState.HEALTHY
        val ex = IOException("boom")
        val chain = chainFor("/api/foo", proceedThrows = ex)

        val thrown = runCatching { interceptor.intercept(chain) }.exceptionOrNull()

        assertTrue(thrown is IOException)
        verify { controller.classifyAndReact(ex, null) }
    }

    @Test
    fun healthy_allowlistedIoExceptionDoesNotClassify() {
        stateFlow.value = HealthState.HEALTHY
        val ex = IOException("boom")
        val chain = chainFor("/health", proceedThrows = ex)

        runCatching { interceptor.intercept(chain) }
        verify(exactly = 0) { controller.classifyAndReact(any(), any()) }
    }
}
