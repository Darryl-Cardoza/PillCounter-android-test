package com.rite.pillcounting.core.utils.logger

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AppLogger].
 *
 * [AppLogger] is a thin wrapper around android.util.Log, which is not implemented in the
 * Android stub JAR used for JVM unit tests. We mock it statically with mockk so the wrapped
 * calls execute (and can be verified) instead of throwing.
 *
 * Note: d() and i() are gated by BuildConfig.DEBUG. For the debug unit-test variant
 * BuildConfig.DEBUG is true, so those branches execute and the verifications below hold.
 */
class AppLoggerTest {

    private val tag = "TestTag"
    private lateinit var logger: AppLogger

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.println(any(), any(), any()) } returns 0
        every { Log.getStackTraceString(any()) } returns "stack trace"

        logger = AppLogger(tag)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // -------------------------------------------------------------------------
    // d()
    // -------------------------------------------------------------------------

    @Test
    fun `d without throwable delegates to Log d with null throwable`() {
        logger.d("debug message")
        verify(exactly = 1) { Log.d(tag, "debug message", null) }
    }

    @Test
    fun `d with throwable delegates to Log d with that throwable`() {
        val throwable = RuntimeException("boom")
        logger.d("debug message", throwable)
        verify(exactly = 1) { Log.d(tag, "debug message", throwable) }
    }

    // -------------------------------------------------------------------------
    // i()
    // -------------------------------------------------------------------------
    // i() routes through logLong (chunked Log.println), not Log.i directly, so
    // that long HL7 payloads aren't silently truncated by Logcat's ~4KB line limit.

    @Test
    fun `i without throwable delegates to Log println with just the message`() {
        logger.i("info message")
        verify(exactly = 1) { Log.println(Log.INFO, tag, "info message") }
    }

    @Test
    fun `i with throwable delegates to Log println with message and stack trace`() {
        val throwable = IllegalStateException("state")
        logger.i("info message", throwable)
        verify(exactly = 1) { Log.getStackTraceString(throwable) }
        verify(exactly = 1) { Log.println(Log.INFO, tag, "info message\nstack trace") }
    }

    // -------------------------------------------------------------------------
    // w()
    // -------------------------------------------------------------------------

    @Test
    fun `w without throwable delegates to Log w with null throwable`() {
        logger.w("warn message")
        verify(exactly = 1) { Log.w(tag, "warn message", null) }
    }

    @Test
    fun `w with throwable delegates to Log w with that throwable`() {
        val throwable = Exception("warn cause")
        logger.w("warn message", throwable)
        verify(exactly = 1) { Log.w(tag, "warn message", throwable) }
    }

    // -------------------------------------------------------------------------
    // e()
    // -------------------------------------------------------------------------

    @Test
    fun `e without throwable delegates to Log e with null throwable`() {
        logger.e("error message")
        verify(exactly = 1) { Log.e(tag, "error message", null) }
    }

    @Test
    fun `e with throwable delegates to Log e with that throwable`() {
        val throwable = RuntimeException("error cause")
        logger.e("error message", throwable)
        verify(exactly = 1) { Log.e(tag, "error message", throwable) }
    }

    // -------------------------------------------------------------------------
    // companion create<T>()
    // -------------------------------------------------------------------------

    @Test
    fun `create uses simple name of reified type as tag`() {
        val created = AppLogger.create<AppLoggerTest>()
        created.e("via factory")
        // Tag should be the simple class name of the reified type.
        verify(exactly = 1) { Log.e("AppLoggerTest", "via factory", null) }
    }

    @Test
    fun `create returns a usable AppLogger instance`() {
        val created = AppLogger.create<String>()
        assertEquals(AppLogger::class.java, created::class.java)
        created.d("string tagged")
        verify(exactly = 1) { Log.d("String", "string tagged", null) }
    }
}
