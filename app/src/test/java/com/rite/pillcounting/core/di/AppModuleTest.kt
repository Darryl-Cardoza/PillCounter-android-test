package com.rite.pillcounting.core.di

import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Unit tests for [AppModule]'s `@Provides` functions.
 *
 * Each testable provider is invoked directly and the result asserted non-null.
 *
 * SKIPPED:
 * - `provideLocationProvider` — constructs `LocationProvider(context)` which requires a
 *   real Android [android.content.Context] (and play-services Location APIs). Not
 *   JVM-testable without instrumentation.
 */
class AppModuleTest {

    @Test
    fun `provideIoDispatcher returns non-null CoroutineDispatcher`() {
        val dispatcher: CoroutineDispatcher = AppModule.provideIoDispatcher()

        assertNotNull(dispatcher)
        // Calling twice should yield the same Dispatchers.IO instance.
        assertSame(AppModule.provideIoDispatcher(), AppModule.provideIoDispatcher())
    }
}
