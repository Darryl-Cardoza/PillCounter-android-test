package com.rite.pillcounting.core.security

import android.content.Context
import android.util.Base64
import com.rite.pillcounting.core.utils.preference.SecurePreferences
import com.rite.pillcounting.feature.dashboard.domain.model.KekInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Base64 as JavaBase64

/**
 * Tests the bootstrap/rotation orchestration logic in [DatabaseKeyProvider].
 *
 * [KeystoreAesGcm] is mocked here (it's `internal`, same module) since real AndroidKeyStore
 * crypto has no functional Robolectric provider and cannot run on the plain JVM — see
 * [KeystoreAesGcmTest] doc comment. This file verifies the *orchestration*: which alias is used
 * when, version-compare no-op behavior, and failure/recovery paths — not the actual cipher math.
 */
class DatabaseKeyProviderTest {

    private val context: Context = mockk(relaxed = true)

    @Before
    fun setUp() {
        mockkConstructor(SecurePreferences::class)
        mockkStatic(Base64::class)
        mockkObject(KeystoreAesGcm)

        every { Base64.encodeToString(any(), any()) } answers {
            JavaBase64.getEncoder().encodeToString(firstArg())
        }
        every { Base64.decode(any<String>(), any()) } answers {
            JavaBase64.getDecoder().decode(firstArg<String>())
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun stubPrefs(wrapped: String?, kekId: String?, version: Int = 0) {
        every { anyConstructed<SecurePreferences>().getString("dek_wrapped") } returns wrapped
        every { anyConstructed<SecurePreferences>().getString("dek_kek_id") } returns kekId
        every { anyConstructed<SecurePreferences>().getInt("dek_kek_version", -1) } returns version
        every { anyConstructed<SecurePreferences>().putString(any(), any()) } returns Unit
        every { anyConstructed<SecurePreferences>().putInt(any(), any()) } returns Unit
        every { anyConstructed<SecurePreferences>().clear() } returns Unit
    }

    // ---- getOrCreateDatabasePassphrase: bootstrap (first launch) ----

    @Test
    fun `first launch generates a 32-byte DEK and wraps it with the bootstrap KEK`() {
        stubPrefs(wrapped = null, kekId = null)
        val wrapSlot = slot<ByteArray>()
        every { KeystoreAesGcm.wrap("com.rite.pillcounting.dek_bootstrap_kek", capture(wrapSlot)) } answers {
            wrapSlot.captured
        }

        val result = DatabaseKeyProvider.getOrCreateDatabasePassphrase(context)

        assertEquals(32, result.size)
        assertArrayEquals(result, wrapSlot.captured)
        verify(exactly = 1) {
            anyConstructed<SecurePreferences>().putString("dek_kek_id", "local-bootstrap")
        }
        verify(exactly = 1) { anyConstructed<SecurePreferences>().putInt("dek_kek_version", 0) }
    }

    @Test
    fun `subsequent launch unwraps existing DEK using the stored kek id's alias`() {
        val dek = ByteArray(32) { it.toByte() }
        val wrappedB64 = JavaBase64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        stubPrefs(wrapped = wrappedB64, kekId = "local-bootstrap")
        every { KeystoreAesGcm.unwrap("com.rite.pillcounting.dek_bootstrap_kek", any()) } returns dek

        val result = DatabaseKeyProvider.getOrCreateDatabasePassphrase(context)

        assertArrayEquals(dek, result)
        verify(exactly = 0) { anyConstructed<SecurePreferences>().putString("dek_wrapped", any()) }
    }

    @Test
    fun `subsequent launch after rotation unwraps using the server kek alias`() {
        val dek = ByteArray(32) { it.toByte() }
        val wrappedB64 = JavaBase64.getEncoder().encodeToString(byteArrayOf(9, 9))
        stubPrefs(wrapped = wrappedB64, kekId = "kek-abc123")
        every { KeystoreAesGcm.unwrap("com.rite.pillcounting.server_kek_kek-abc123", any()) } returns dek

        val result = DatabaseKeyProvider.getOrCreateDatabasePassphrase(context)

        assertArrayEquals(dek, result)
    }

    @Test
    fun `unwrap failure re-keys the database instead of crashing`() {
        val wrappedB64 = JavaBase64.getEncoder().encodeToString(byteArrayOf(1))
        stubPrefs(wrapped = wrappedB64, kekId = "local-bootstrap")
        every { KeystoreAesGcm.unwrap(any(), any()) } throws IllegalStateException("Keystore key not found")
        every { KeystoreAesGcm.wrap(any(), any()) } answers { secondArg() }

        val result = DatabaseKeyProvider.getOrCreateDatabasePassphrase(context)

        assertEquals(32, result.size)
        verify(exactly = 1) { anyConstructed<SecurePreferences>().clear() }
        verify(exactly = 1) { anyConstructed<SecurePreferences>().putString("dek_kek_id", "local-bootstrap") }
    }

    // ---- rotateKekIfNewer ----

    @Test
    fun `rotation is a no-op when incoming version is not newer`() {
        stubPrefs(wrapped = "anything", kekId = "kek-old", version = 3)
        val kekInfo = KekInfo(keyId = "kek-new", version = 3, algorithm = "AES-256-GCM", keyMaterial = "abcd")

        DatabaseKeyProvider.rotateKekIfNewer(context, kekInfo)

        verify(exactly = 0) { KeystoreAesGcm.importKeystoreKey(any(), any()) }
        verify(exactly = 0) { anyConstructed<SecurePreferences>().putString("dek_kek_id", any()) }
    }

    @Test
    fun `rotation is a no-op when incoming version is older`() {
        stubPrefs(wrapped = "anything", kekId = "kek-old", version = 5)
        val kekInfo = KekInfo(keyId = "kek-new", version = 2, algorithm = "AES-256-GCM", keyMaterial = "abcd")

        DatabaseKeyProvider.rotateKekIfNewer(context, kekInfo)

        verify(exactly = 0) { KeystoreAesGcm.importKeystoreKey(any(), any()) }
    }

    @Test
    fun `rotation imports new kek, unwraps with old alias, rewraps with new alias, deletes old alias`() {
        val oldWrappedB64 = JavaBase64.getEncoder().encodeToString(byteArrayOf(5, 5, 5))
        stubPrefs(wrapped = oldWrappedB64, kekId = "local-bootstrap", version = 0)
        val dek = ByteArray(32) { it.toByte() }
        val rawKekBytes = "raw-server-kek".toByteArray()
        val kekInfo = KekInfo(
            keyId = "kek-4fdbc6fc",
            version = 1,
            algorithm = "AES-256-GCM",
            keyMaterial = JavaBase64.getEncoder().encodeToString(rawKekBytes)
        )

        every { KeystoreAesGcm.importKeystoreKey("com.rite.pillcounting.server_kek_kek-4fdbc6fc", any()) } returns Unit
        every { KeystoreAesGcm.unwrap("com.rite.pillcounting.dek_bootstrap_kek", any()) } returns dek
        every { KeystoreAesGcm.wrap("com.rite.pillcounting.server_kek_kek-4fdbc6fc", dek) } returns byteArrayOf(7, 7)
        every { KeystoreAesGcm.deleteKeystoreKey(any()) } returns Unit

        DatabaseKeyProvider.rotateKekIfNewer(context, kekInfo)

        verify(exactly = 1) {
            KeystoreAesGcm.importKeystoreKey("com.rite.pillcounting.server_kek_kek-4fdbc6fc", any())
        }
        verify(exactly = 1) { KeystoreAesGcm.unwrap("com.rite.pillcounting.dek_bootstrap_kek", any()) }
        verify(exactly = 1) { KeystoreAesGcm.wrap("com.rite.pillcounting.server_kek_kek-4fdbc6fc", dek) }
        verify(exactly = 1) { KeystoreAesGcm.deleteKeystoreKey("com.rite.pillcounting.dek_bootstrap_kek") }
        verify(exactly = 1) { anyConstructed<SecurePreferences>().putString("dek_kek_id", "kek-4fdbc6fc") }
        verify(exactly = 1) { anyConstructed<SecurePreferences>().putInt("dek_kek_version", 1) }
    }

    @Test
    fun `second rotation deletes the previous server kek alias, not the bootstrap alias`() {
        val oldWrappedB64 = JavaBase64.getEncoder().encodeToString(byteArrayOf(3))
        stubPrefs(wrapped = oldWrappedB64, kekId = "kek-old-id", version = 1)
        val dek = ByteArray(32)
        val kekInfo = KekInfo(
            keyId = "kek-new-id",
            version = 2,
            algorithm = "AES-256-GCM",
            keyMaterial = JavaBase64.getEncoder().encodeToString(byteArrayOf(1))
        )

        every { KeystoreAesGcm.importKeystoreKey(any(), any()) } returns Unit
        every { KeystoreAesGcm.unwrap("com.rite.pillcounting.server_kek_kek-old-id", any()) } returns dek
        every { KeystoreAesGcm.wrap(any(), dek) } returns byteArrayOf(1)
        every { KeystoreAesGcm.deleteKeystoreKey(any()) } returns Unit

        DatabaseKeyProvider.rotateKekIfNewer(context, kekInfo)

        verify(exactly = 1) { KeystoreAesGcm.deleteKeystoreKey("com.rite.pillcounting.server_kek_kek-old-id") }
        verify(exactly = 0) { KeystoreAesGcm.deleteKeystoreKey("com.rite.pillcounting.dek_bootstrap_kek") }
    }

    @Test
    fun `rotation failure rolls back the newly imported kek and leaves old key intact`() {
        val oldWrappedB64 = JavaBase64.getEncoder().encodeToString(byteArrayOf(3))
        stubPrefs(wrapped = oldWrappedB64, kekId = "local-bootstrap", version = 0)
        val kekInfo = KekInfo(
            keyId = "kek-broken",
            version = 1,
            algorithm = "AES-256-GCM",
            keyMaterial = JavaBase64.getEncoder().encodeToString(byteArrayOf(1))
        )

        every { KeystoreAesGcm.importKeystoreKey(any(), any()) } returns Unit
        every { KeystoreAesGcm.unwrap(any(), any()) } throws IllegalStateException("boom")
        every { KeystoreAesGcm.deleteKeystoreKey(any()) } returns Unit

        DatabaseKeyProvider.rotateKekIfNewer(context, kekInfo)

        verify(exactly = 1) { KeystoreAesGcm.deleteKeystoreKey("com.rite.pillcounting.server_kek_kek-broken") }
        verify(exactly = 0) { anyConstructed<SecurePreferences>().putString("dek_kek_id", "kek-broken") }
    }

    @Test
    fun `rotation is skipped when no existing wrapped DEK is found`() {
        stubPrefs(wrapped = null, kekId = null, version = -1)
        val kekInfo = KekInfo(keyId = "kek-x", version = 1, algorithm = "AES-256-GCM", keyMaterial = "abcd")

        DatabaseKeyProvider.rotateKekIfNewer(context, kekInfo)

        verify(exactly = 0) { KeystoreAesGcm.importKeystoreKey(any(), any()) }
    }
}
