package com.dispensesure.retail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogoutTeardownTest {

    @Test
    fun `a failed cleanup still navigates to the auth graph`() {
        var navigated = false
        val errors = mutableListOf<String>()

        runLogoutTeardown(
            cleanup = { error("clearTokens failed") },
            navigate = { navigated = true },
            onError = { message, _ -> errors += message },
        )

        assertTrue(navigated)
        assertEquals(listOf("Session teardown cleanup failed"), errors)
    }

    @Test
    fun `a failed navigation is reported instead of thrown`() {
        var cleaned = false
        val errors = mutableListOf<String>()

        runLogoutTeardown(
            cleanup = { cleaned = true },
            navigate = { error("nav graph not set") },
            onError = { message, _ -> errors += message },
        )

        assertTrue(cleaned)
        assertEquals(listOf("Navigation to auth graph failed after session expiry"), errors)
    }
}
