package com.dispensesure.retail.core.utils.logger.destination

import android.util.Log
import com.dispensesure.retail.core.utils.logger.LogDestination
import com.dispensesure.retail.core.utils.logger.LogEntry
import com.dispensesure.retail.core.utils.logger.LogLevel
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Unit tests for [CompositeLogDestination]'s fan-out and per-destination failure isolation. */
class CompositeLogDestinationTest {

    private class RecordingDestination(private val failWith: Exception? = null) : LogDestination {
        val received = mutableListOf<LogEntry>()
        override fun write(entry: LogEntry) {
            failWith?.let { throw it }
            received += entry
        }
    }

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun entry() = LogEntry(
        timestampMillis = 0L,
        level = LogLevel.ERROR,
        fileName = "Foo.kt",
        className = "Foo",
        methodName = "bar",
        message = "something happened"
    )

    @Test
    fun `write forwards the entry to every destination`() {
        val a = RecordingDestination()
        val b = RecordingDestination()
        val composite = CompositeLogDestination(a, b)

        val logEntry = entry()
        composite.write(logEntry)

        assertEquals(listOf(logEntry), a.received)
        assertEquals(listOf(logEntry), b.received)
    }

    @Test
    fun `a failing destination does not prevent the others from receiving the entry`() {
        val failing = RecordingDestination(failWith = RuntimeException("provider not initialized"))
        val working = RecordingDestination()
        val composite = CompositeLogDestination(failing, working)

        // Must not throw despite `failing` throwing internally.
        composite.write(entry())

        assertEquals(1, working.received.size)
    }
}
