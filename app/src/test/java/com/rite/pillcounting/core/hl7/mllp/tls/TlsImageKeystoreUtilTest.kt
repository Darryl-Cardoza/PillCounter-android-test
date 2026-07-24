package com.rite.pillcounting.core.hl7.mllp.tls

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStore

/**
 * Unit tests for [TlsImageKeystoreUtil].
 *
 * The object relies on the real "AndroidKeyStore" JCA provider and BouncyCastle
 * X.509 certificate generation, neither of which is available/registered on a
 * plain JVM unit-test run (no emulator/device, no security provider mocking
 * scaffolding exists elsewhere in this test suite for `AndroidKeyStore`).
 * Because of this, [TlsImageKeystoreUtil.ensureKeystore] and
 * [TlsImageKeystoreUtil.password] cannot be driven to their happy path here —
 * doing so would require either a Robolectric shadow that doesn't exist for
 * AndroidKeyStore, or modifying production code to inject the KeyStore/crypto
 * provider, which is more than a "tiny seam" and was avoided per instructions.
 *
 * What IS verified here, without touching production code:
 *  - [TlsImageKeystoreUtil.fingerprint] genuinely exercises its try/catch branch:
 *    since "AndroidKeyStore" cannot be loaded on the JVM, the underlying call chain
 *    throws, and fingerprint() must catch it and return the "UNKNOWN" sentinel
 *    rather than propagating.
 *  - The private `decrypt` format validation (`require(parts.size == 2)`) is
 *    exercised via reflection with a mocked KeyStore, confirming the
 *    "Invalid stored password format" guard actually fires for malformed input,
 *    and that well-formed-but-non-numeric input still reaches key lookup (fails
 *    later for an independent reason), proving the format check specifically
 *    gates on the colon-split shape.
 */
class TlsImageKeystoreUtilTest {

    private val context: Context = mockk(relaxed = true)

    @Test
    fun `fingerprint returns UNKNOWN sentinel when AndroidKeyStore provider is unavailable`() {
        // On a plain JVM (no Robolectric AndroidKeyStore shadow registered),
        // ensureKeystore() -> getOrCreateKeystorePassword() -> KeyStore.getInstance("AndroidKeyStore")
        // will throw java.security.KeyStoreException, which fingerprint() must catch.
        val result = TlsImageKeystoreUtil.fingerprint(context)

        assertEquals("UNKNOWN", result)
    }

    @Test
    fun `decrypt rejects stored value without exactly one colon separator`() {
        val method = TlsImageKeystoreUtil::class.java
            .getDeclaredMethod(
                "decrypt",
                String::class.java,
                KeyStore::class.java,
                String::class.java
            )
        method.isAccessible = true

        val fakeKeyStore = mockk<KeyStore>(relaxed = true)

        // No colon at all -> parts.size == 1
        val ex1 = runCatching {
            method.invoke(TlsImageKeystoreUtil, "nocolonhere", fakeKeyStore, "alias")
        }.exceptionOrNull()
        assertTrue(
            "Expected an invocation failure for malformed (no colon) input",
            ex1 != null
        )
        assertTrue(
            unwrap(ex1) is IllegalArgumentException
        )
        assertEquals("Invalid stored password format", unwrap(ex1)?.message)

        // Two colons -> parts.size == 3
        val ex2 = runCatching {
            method.invoke(TlsImageKeystoreUtil, "a:b:c", fakeKeyStore, "alias")
        }.exceptionOrNull()
        assertTrue(unwrap(ex2) is IllegalArgumentException)
        assertEquals("Invalid stored password format", unwrap(ex2)?.message)
    }

    @Test
    fun `decrypt with well-formed two-part input passes the format guard`() {
        val method = TlsImageKeystoreUtil::class.java
            .getDeclaredMethod(
                "decrypt",
                String::class.java,
                KeyStore::class.java,
                String::class.java
            )
        method.isAccessible = true

        val fakeKeyStore = mockk<KeyStore>(relaxed = true)

        // Exactly one colon: the `require(parts.size == 2)` guard must pass,
        // so any resulting failure must come from a *different* cause
        // (base64 decoding / key retrieval), not the IllegalArgumentException
        // format message used for malformed input.
        val ex = runCatching {
            method.invoke(TlsImageKeystoreUtil, "aXY=:bXY=", fakeKeyStore, "alias")
        }.exceptionOrNull()

        val cause = unwrap(ex)
        val isFormatError = cause is IllegalArgumentException &&
            cause.message == "Invalid stored password format"
        assertTrue(
            "Well-formed two-part input must not fail on the format guard",
            !isFormatError
        )
    }

    private fun unwrap(t: Throwable?): Throwable? {
        var cur = t
        while (cur is java.lang.reflect.InvocationTargetException && cur.targetException != null) {
            cur = cur.targetException
        }
        return cur
    }
}
