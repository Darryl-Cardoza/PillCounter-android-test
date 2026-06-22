package com.rite.pillcounting.core.utils.notification.di

import org.junit.Test

/**
 * Unit tests for [FcmModule]'s `@Provides` functions.
 *
 * SKIPPED:
 * - `provideFcmService` — constructs `FCMService(context)` which requires a real Android
 *   [android.content.Context] (Firebase Messaging / NotificationManager). Not JVM-testable
 *   without instrumentation, so there are no coverable providers here.
 *
 * This placeholder documents the intentional skip; it contains no executable provider tests.
 */
class FcmModuleTest {

    @Test
    fun `provideFcmService is skipped - requires real Android Context`() {
        // No-op: documented skip. See class KDoc for rationale.
    }
}
