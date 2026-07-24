package com.rite.pillcounting.core.security

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [RuntimeUnit].
 *
 * NOTE ON SCOPE: RuntimeUnit's key-material lifecycle (`activateIfNeeded`, `material`,
 * seal/open, Android Keystore key generation, SharedPreferences persistence) depends on
 * `android.security.keystore.KeyGenParameterSpec`, `java.security.KeyStore` with the
 * "AndroidKeyStore" provider, and `android.util.Base64` — none of which exist in a plain
 * JVM unit-test environment and none of which are provided by Robolectric's default
 * shadow set without additional shadow/keystore setup not present in this project's test
 * configuration. Attempting to exercise those paths on the JVM throws
 * `java.security.NoSuchProviderException` / `UnsatisfiedLinkError` before any of
 * RuntimeUnit's own logic runs, so those branches cannot be meaningfully unit tested here
 * without adding new test infrastructure (out of scope for this change; no production
 * code was modified).
 *
 * What IS pure JVM logic and is fully covered below:
 *  - The security-gate state machine (`grantClearance` / `revokeClearance`) and the
 *    `check(securityCleared)` guards in `activateIfNeeded()` and `material()`, which throw
 *    before ever touching Android Keystore/SharedPreferences.
 */
class RuntimeUnitTest {

    private lateinit var context: Context
    private lateinit var runtimeUnit: RuntimeUnit

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        runtimeUnit = RuntimeUnit(context)
    }

    @Test
    fun `activateIfNeeded throws IllegalStateException when clearance was never granted`() {
        val exception = assertThrows(IllegalStateException::class.java) {
            runtimeUnit.activateIfNeeded()
        }
        assertEquals(
            "RuntimeUnit: activation blocked — security violations present",
            exception.message
        )
    }

    @Test
    fun `material throws IllegalStateException when clearance was never granted`() {
        val exception = assertThrows(IllegalStateException::class.java) {
            runtimeUnit.material()
        }
        assertEquals(
            "RuntimeUnit: key retrieval blocked — security violations present",
            exception.message
        )
    }

    @Test
    fun `activateIfNeeded throws after clearance is revoked following a grant`() {
        runtimeUnit.grantClearance()
        runtimeUnit.revokeClearance()

        val exception = assertThrows(IllegalStateException::class.java) {
            runtimeUnit.activateIfNeeded()
        }
        assertEquals(
            "RuntimeUnit: activation blocked — security violations present",
            exception.message
        )
    }

    @Test
    fun `material throws after clearance is revoked following a grant`() {
        runtimeUnit.grantClearance()
        runtimeUnit.revokeClearance()

        val exception = assertThrows(IllegalStateException::class.java) {
            runtimeUnit.material()
        }
        assertEquals(
            "RuntimeUnit: key retrieval blocked — security violations present",
            exception.message
        )
    }

    @Test
    fun `revokeClearance without a prior grant keeps the gate blocked`() {
        runtimeUnit.revokeClearance()

        assertThrows(IllegalStateException::class.java) {
            runtimeUnit.activateIfNeeded()
        }
    }

    @Test
    fun `grantClearance followed by another grantClearance still passes the gate check`() {
        runtimeUnit.grantClearance()
        runtimeUnit.grantClearance()

        // Passing the gate means execution proceeds past `check(securityCleared)`
        // into Keystore-dependent code, which is unavailable on the plain JVM and
        // throws a provider/security exception rather than the gate's
        // IllegalStateException — this confirms the gate itself let it through.
        val exception = assertThrows(Throwable::class.java) {
            runtimeUnit.activateIfNeeded()
        }
        assertFalse(exception is IllegalStateException && exception.message?.contains("blocked") == true)
    }
}
