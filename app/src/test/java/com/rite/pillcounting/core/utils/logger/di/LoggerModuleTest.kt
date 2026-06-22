package com.rite.pillcounting.core.utils.logger.di

import org.junit.Test

/**
 * Unit tests for [LoggerModule]'s `@Provides` functions.
 *
 * SKIPPED:
 * - `providePerformanceLogger` — constructs `PerformanceLogger(context)` which requires a
 *   real Android [android.content.Context]. Not JVM-testable without instrumentation, so
 *   there are no coverable providers here.
 *
 * This placeholder documents the intentional skip; it contains no executable provider tests.
 */
class LoggerModuleTest {

    @Test
    fun `providePerformanceLogger is skipped - requires real Android Context`() {
        // No-op: documented skip. See class KDoc for rationale.
    }
}
