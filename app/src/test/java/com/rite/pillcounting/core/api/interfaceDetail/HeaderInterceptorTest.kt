package com.rite.pillcounting.core.api.interfaceDetail

import com.rite.pillcounting.core.security.RuntimeUnit
import com.rite.pillcounting.core.utils.constants.URLConstant
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [HeaderInterceptor].
 *
 * Covers: header injection on the outgoing request when the server key is available,
 * and the catch branch where [RuntimeUnit.material] throws (empty key fallback).
 */
class HeaderInterceptorTest {

    private lateinit var runtimeUnit: RuntimeUnit
    private lateinit var chain: Interceptor.Chain
    private lateinit var interceptor: HeaderInterceptor

    private val incomingRequest = Request.Builder()
        .url("https://example.com/api/resource")
        .build()

    @Before
    fun setup() {
        runtimeUnit = mockk()
        chain = mockk()
        interceptor = HeaderInterceptor(runtimeUnit)

        every { chain.request() } returns incomingRequest
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun response(forRequest: Request): Response = Response.Builder()
        .request(forRequest)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .build()

    @Test
    fun `adds server key and content type headers when material succeeds`() {
        every { runtimeUnit.material() } returns "SERVER-KEY-123"
        val captured = slot<Request>()
        every { chain.proceed(capture(captured)) } answers { response(captured.captured) }

        val result = interceptor.intercept(chain)

        val outgoing = captured.captured
        assertEquals("SERVER-KEY-123", outgoing.header("X-Server-Key"))
        assertEquals(URLConstant.CONTENT_TYPE, outgoing.header("Content-Type"))
        assertEquals(200, result.code)
    }

    @Test
    fun `falls back to empty server key when material throws`() {
        every { runtimeUnit.material() } throws RuntimeException("blocked")
        val captured = slot<Request>()
        val expected = response(incomingRequest)
        every { chain.proceed(capture(captured)) } returns expected

        val result = interceptor.intercept(chain)

        val outgoing = captured.captured
        assertEquals("", outgoing.header("X-Server-Key"))
        assertEquals(URLConstant.CONTENT_TYPE, outgoing.header("Content-Type"))
        assertSame(expected, result)
    }
}
