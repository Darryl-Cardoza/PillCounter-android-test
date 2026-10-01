package com.dispensesure.retail.core.utils.logger

import android.util.Log
import com.dispensesure.retail.BuildConfig
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AppLogger] — both the thin Logcat wrapper (instance methods) and the
 * centralized logging hub it also owns via its companion object (level filtering, call-site
 * resolution, dispatch to a [LogDestination]).
 *
 * [AppLogger] wraps android.util.Log, which is not implemented in the Android stub JAR used for
 * JVM unit tests. We mock it statically with mockk so the wrapped calls execute (and can be
 * verified) instead of throwing.
 *
 * Note: d() and i() are gated by BuildConfig.DEBUG. These tests run for both the debug and
 * release unit-test variants, so those calls are expected exactly once under debug and never
 * under release.
 */
class AppLoggerTest {

    private val tag = "TestTag"

    // d()/i() only reach android.util.Log in debug builds.
    private val debugCalls = if (BuildConfig.DEBUG) 1 else 0

    private lateinit var logger: AppLogger

    private class RecordingDestination : LogDestination {
        val entries = mutableListOf<LogEntry>()
        override fun write(entry: LogEntry) {
            entries += entry
        }
    }

    private lateinit var destination: RecordingDestination
    private val originalMinimumLevel = LoggerConfig.minimumLogLevel
    private val originalOperatorName = LoggerConfig.operatorName

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

        destination = RecordingDestination()
        AppLogger.setDestinationForTest(destination)
    }

    @After
    fun tearDown() {
        unmockkAll()
        AppLogger.setDestinationForTest(null)
        LoggerConfig.minimumLogLevel = originalMinimumLevel
        LoggerConfig.operatorName = originalOperatorName
    }

    // -------------------------------------------------------------------------
    // d()
    // -------------------------------------------------------------------------

    @Test
    fun `d without throwable delegates to Log d with null throwable`() {
        logger.d("debug message")
        verify(exactly = debugCalls) { Log.d(tag, "debug message", null) }
    }

    @Test
    fun `d with throwable delegates to Log d with that throwable`() {
        val throwable = RuntimeException("boom")
        logger.d("debug message", throwable)
        verify(exactly = debugCalls) { Log.d(tag, "debug message", throwable) }
    }

    // -------------------------------------------------------------------------
    // i()
    // -------------------------------------------------------------------------
    // i() routes through logLong (chunked Log.println), not Log.i directly, so
    // that long HL7 payloads aren't silently truncated by Logcat's ~4KB line limit.

    @Test
    fun `i without throwable delegates to Log println with just the message`() {
        logger.i("info message")
        verify(exactly = debugCalls) { Log.println(Log.INFO, tag, "info message") }
    }

    @Test
    fun `i with throwable delegates to Log println with message and stack trace`() {
        val throwable = IllegalStateException("state")
        logger.i("info message", throwable)
        verify(exactly = debugCalls) { Log.getStackTraceString(throwable) }
        verify(exactly = debugCalls) { Log.println(Log.INFO, tag, "info message\nstack trace") }
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
        verify(exactly = debugCalls) { Log.d("String", "string tagged", null) }
    }

    // -------------------------------------------------------------------------
    // Centralized logging (companion object): level filtering, call-site resolution,
    // dispatch to whichever LogDestination is configured.
    // -------------------------------------------------------------------------

    @Test
    fun `log below minimum level is filtered out of the destination`() {
        LoggerConfig.minimumLogLevel = LogLevel.ERROR

        logger.w("a warning")

        assertTrue(destination.entries.isEmpty())
    }

    @Test
    fun `log at or above minimum level reaches the destination`() {
        LoggerConfig.minimumLogLevel = LogLevel.WARN

        logger.w("a warning")
        logger.e("an error")

        assertEquals(2, destination.entries.size)
    }

    @Test
    fun `no destination configured is a safe no-op`() {
        AppLogger.setDestinationForTest(null)

        // Must not throw even though nothing is configured.
        logger.e("an error")
    }

    @Test
    fun `entry carries the message, tag as class, and translated throwable`() {
        LoggerConfig.minimumLogLevel = LogLevel.VERBOSE
        val throwable = java.net.SocketTimeoutException("timeout")

        AppLogger("LoginRepository").e("login failed", throwable)

        val entry = destination.entries.single()
        assertEquals(LogLevel.ERROR, entry.level)
        assertEquals("LoginRepository", entry.className)
        assertEquals("login failed", entry.message)
        assertEquals(throwable, entry.throwable)
        assertEquals(
            "The request timed out while communicating with the server.",
            entry.humanReadableError
        )
    }

    @Test
    fun `entry carries the operator name when LoggerConfig has one set`() {
        LoggerConfig.minimumLogLevel = LogLevel.VERBOSE
        LoggerConfig.operatorName = "Jane Doe"

        logger.e("boom")

        assertEquals("Jane Doe", destination.entries.single().operatorName)
    }

    @Test
    fun `entry has a null operator name when LoggerConfig's is null`() {
        LoggerConfig.minimumLogLevel = LogLevel.VERBOSE
        LoggerConfig.operatorName = null

        logger.e("boom")

        assertNull(destination.entries.single().operatorName)
    }

    @Test
    fun `entry has a null operator name when LoggerConfig's is blank`() {
        LoggerConfig.minimumLogLevel = LogLevel.VERBOSE
        LoggerConfig.operatorName = "   "

        logger.e("boom")

        assertNull(destination.entries.single().operatorName)
    }

    @Test
    fun `entry without a throwable has no human readable error`() {
        LoggerConfig.minimumLogLevel = LogLevel.VERBOSE

        logger.w("just a warning")

        val entry = destination.entries.single()
        assertNull(entry.throwable)
        assertNull(entry.humanReadableError)
    }

    @Test
    fun `call site resolution reports this test class and method, not AppLogger itself`() {
        LoggerConfig.minimumLogLevel = LogLevel.VERBOSE

        logger.e("boom")

        val entry = destination.entries.single()
        assertEquals("AppLoggerTest.kt", entry.fileName)
        assertTrue(
            "methodName should be the calling test method, was: ${entry.methodName}",
            entry.methodName.contains("call site resolution")
        )
    }

    @Test
    fun `d and i also reach the destination when the configured level allows it`() {
        LoggerConfig.minimumLogLevel = LogLevel.VERBOSE

        logger.d("debug")
        logger.i("info")

        assertEquals(2, destination.entries.size)
        assertEquals(LogLevel.DEBUG, destination.entries[0].level)
        assertEquals(LogLevel.INFO, destination.entries[1].level)
    }

    @Test
    fun `a throwing destination never propagates into the caller`() {
        AppLogger.setDestinationForTest(object : LogDestination {
            override fun write(entry: LogEntry) = throw IllegalStateException("destination broke")
        })

        // Would previously escape from inside the caller's own catch block.
        logger.e("something failed", RuntimeException("original"))

        verify(exactly = 1) { Log.e(tag, "something failed", any()) }
    }
}
