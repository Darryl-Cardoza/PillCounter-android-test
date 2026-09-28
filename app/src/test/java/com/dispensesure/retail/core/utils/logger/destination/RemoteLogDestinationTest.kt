package com.dispensesure.retail.core.utils.logger.destination

import com.dispensesure.retail.core.utils.logger.LogEntry
import com.dispensesure.retail.core.utils.logger.LogLevel
import org.junit.Test

/**
 * Unit tests for [RemoteLogDestination]. Its [RemoteLogDestination.write] is currently a no-op
 * stub (no provider SDK wired in yet — see the commented-out line in `AppLogger.init()`), so
 * these only pin down that it never throws and stays silent for non-ERROR entries; once a
 * provider is integrated, add assertions on the actual SDK call here.
 */
class RemoteLogDestinationTest {

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
    fun `write does not throw for an ERROR entry`() {
        RemoteLogDestination().write(entry(LogLevel.ERROR, RuntimeException("boom")))
    }

    @Test
    fun `write does not throw for a non-ERROR entry`() {
        RemoteLogDestination().write(entry(LogLevel.WARN))
    }
}
