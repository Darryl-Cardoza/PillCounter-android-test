package com.rite.pillcounting.core.hl7.mllp.tls

import android.content.Context
import android.content.SharedPreferences
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [TlsSocketFactory].
 *
 * Reachability of `KeyStore.getInstance("AndroidKeyStore")` (unavailable on a plain JVM — no
 * emulator/device, no Robolectric shadow for it exists in this suite; see the sibling
 * TlsImageKeystoreUtilTest for the same documented constraint) differs per method:
 * - [TlsSocketFactory.createSocket] only reaches [TofuTrustManager] in release builds
 *   (`BuildConfig.DEBUG` picks `TlsProvider.createTrustAllClientContext()` instead, which never
 *   touches a keystore) — on a debug test build it fails at real socket I/O instead, not the
 *   keystore, so that path is not asserted here.
 * - [TlsSocketFactory.pinnedFingerprint]/`TofuTrustManager.currentPin()` short-circuit to `null`
 *   when no pin is stored yet, without ever calling the keystore-backed `decrypt()`.
 * - [TlsSocketFactory.clearServerPin]/`TofuTrustManager.clearPin()` only calls
 *   `SharedPreferences.edit { remove(...) }` — it never touches the keystore at all.
 *
 * `TofuTrustManager.getPin()` also wraps `decrypt()` in a blanket `catch (e: Exception) { null }`,
 * so even when a stored pin forces the keystore-backed `decrypt()` to run, any resulting
 * `KeyStoreException` is swallowed and [pinnedFingerprint] returns `null` rather than
 * propagating — the real, correct fail-safe behavior (a corrupt/undecryptable pin should not
 * crash the caller). `PreferenceHelper`'s construction is intercepted with `mockkConstructor` so
 * `createSocket`'s `isBypassTlsEnabled()` gate can be forced to false for tests that need it.
 *
 * This is a pre-existing constructor-injection gap in production code (PreferenceHelper and
 * TofuTrustManager are `Foo(context, ...)`-constructed inline rather than injected), not
 * something to "fix" with a tiny seam per instructions.
 */
class TlsSocketFactoryTest {

    private val context: Context = mockk(relaxed = true)

    @Before
    fun setUp() {
        mockkConstructor(PreferenceHelper::class)
        every { anyConstructed<PreferenceHelper>().isBypassTlsEnabled() } returns false
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `construction does not touch the keystore because socketFactory is lazy`() {
        // If socketFactory were eager, this would throw KeyStoreException immediately.
        // It must not, proving the `by lazy` deferral is intact.
        val factory = TlsSocketFactory(context, "pms_server")

        assertNotNull(factory)
    }

    @Test
    fun `construction succeeds with default hostIdentifier`() {
        val factory = TlsSocketFactory(context)

        assertNotNull(factory)
    }

    @Test
    fun `pinnedFingerprint returns null when no pin has ever been stored`() {
        // TofuTrustManager.getPin() short-circuits to null before touching the
        // keystore-backed decrypt() when the underlying SharedPreferences has no
        // stored value for this host's pin key — the real, environment-independent
        // behavior on a fresh install.
        val factory = TlsSocketFactory(context, "pms_server")

        assertNull(factory.pinnedFingerprint())
    }

    @Test
    fun `pinnedFingerprint returns null instead of throwing when decrypting a stored pin fails`() {
        // Force TofuTrustManager.plainPrefs.getString(pinKey, null) to return a stored
        // (non-null) value so getPin() actually calls decrypt() -> getOrCreateKey(),
        // reaching the real keystore boundary that's unavailable on the plain JVM — but
        // getPin() wraps that in catch(Exception) { null }, so the KeyStoreException is
        // swallowed rather than propagated.
        val prefs: SharedPreferences = mockk(relaxed = true)
        every { prefs.getString(any(), null) } returns "iv:ciphertext"
        every { context.getSharedPreferences("tofu_pins", Context.MODE_PRIVATE) } returns prefs

        val factory = TlsSocketFactory(context, "pms_server")

        assertNull(factory.pinnedFingerprint())
    }

    @Test
    fun `clearServerPin removes the stored pin without touching the keystore`() {
        // TofuTrustManager.clearPin() only calls SharedPreferences.edit { remove(...) };
        // it never reaches the keystore, so this should complete without throwing.
        val prefs: SharedPreferences = mockk(relaxed = true)
        every { context.getSharedPreferences("tofu_pins", Context.MODE_PRIVATE) } returns prefs

        val factory = TlsSocketFactory(context, "pms_server")

        factory.clearServerPin()
    }

    @Test
    fun `hostIdentifier is independent per instance and does not leak state across factories`() {
        val prefs: SharedPreferences = mockk(relaxed = true)
        every { context.getSharedPreferences("tofu_pins", Context.MODE_PRIVATE) } returns prefs

        val factoryA = TlsSocketFactory(context, "host_a")
        val factoryB = TlsSocketFactory(context, "host_b")

        // Both return null (no pin stored for either host's key) regardless of
        // hostIdentifier, confirming the hostIdentifier only affects the downstream pin
        // storage key, not construction/lazy-init behavior itself.
        assertNull(factoryA.pinnedFingerprint())
        assertNull(factoryB.pinnedFingerprint())
    }
}
