package com.dispensesure.retail.core.utils.logger.destination

import android.content.Context
import android.util.Log
import com.dispensesure.retail.core.utils.logger.LogEntry
import com.dispensesure.retail.core.utils.logger.LogLevel
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [RemoteLogDestination]. [RemoteLogDestination.write] only ever launches the
 * actual POST on a background coroutine and swallows any failure inside it (see the class doc),
 * so these pin down that the synchronous call itself never throws — for an ERROR entry with and
 * without a throwable, and that it stays silent for non-ERROR entries — regardless of the
 * network/Android environment behind it.
 */
class RemoteLogDestinationTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        context = mockk(relaxed = true)
        every { context.applicationContext } returns context
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun entry(level: LogLevel, throwable: Throwable? = null) = LogEntry(
        timestampMillis = 0L,
        level = level,
        fileName = "Foo.kt",
        className = "Foo",
        methodName = "bar",
        message = "something happened",
        throwable = throwable
    )

    @Test
    fun `write does not throw for an ERROR entry with a throwable`() {
        RemoteLogDestination(context).write(entry(LogLevel.ERROR, RuntimeException("boom")))
    }

    @Test
    fun `write does not throw for an ERROR entry without a throwable`() {
        RemoteLogDestination(context).write(entry(LogLevel.ERROR))
    }

    @Test
    fun `write does not throw for a non-ERROR entry`() {
        RemoteLogDestination(context).write(entry(LogLevel.WARN))
    }
}
