package com.rite.pillcounting.core.hl7.mllp.tls

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.crypto.KeyGenerator
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TofuTrustManagerTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var systemTrustManager: X509TrustManager
    private lateinit var keyStore: KeyStore
    private lateinit var pinsMap: MutableMap<String, String?>

    private val hostId = "example.com:443"

    @Before
    fun setup() {
        pinsMap = mutableMapOf()

        // ── Base64 (android.util.Base64) — delegate to java.util.Base64 semantics ──
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any(), any()) } answers {
            java.util.Base64.getEncoder().encodeToString(firstArg())
        }
        every { Base64.decode(any<String>(), any()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }

        // ── SharedPreferences backed by an in-memory map ──
        editor = mockk(relaxed = true)
        every { editor.putString(any(), any()) } answers {
            pinsMap[firstArg()] = secondArg()
            editor
        }
        every { editor.remove(any()) } answers {
            pinsMap.remove(firstArg())
            editor
        }
        every { editor.apply() } answers { }
        every { editor.commit() } returns true

        prefs = mockk()
        every { prefs.edit() } returns editor
        every { prefs.getString(any(), any()) } answers {
            pinsMap[firstArg()] ?: secondArg()
        }

        context = mockk(relaxed = true)
        every { context.getSharedPreferences("tofu_pins", Context.MODE_PRIVATE) } returns prefs

        // ── AndroidKeyStore -> stub out with a real in-memory AES key via JCE ──
        val realKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        keyStore = mockk(relaxed = true)
        mockkStatic(KeyStore::class)
        every { KeyStore.getInstance("AndroidKeyStore") } returns keyStore
        every { keyStore.load(null) } answers { }
        every { keyStore.getKey(any(), any()) } returns realKey

        // KeyGenerator.getInstance(algorithm, "AndroidKeyStore") -> not actually invoked
        // because getKey() always returns a key in these tests (key already "exists").
        mockkStatic(KeyGenerator::class)
        every {
            KeyGenerator.getInstance(any<String>(), "AndroidKeyStore")
        } answers {
            KeyGenerator.getInstance(firstArg<String>())
        }

        // ── System trust manager used via TrustManagerFactory ──
        systemTrustManager = mockk()
        mockkStatic(TrustManagerFactory::class)
        val factory = mockk<TrustManagerFactory>(relaxed = true)
        every { TrustManagerFactory.getDefaultAlgorithm() } returns "PKIX"
        every { TrustManagerFactory.getInstance("PKIX") } returns factory
        every { factory.init(null as KeyStore?) } answers { }
        every { factory.trustManagers } returns arrayOf(systemTrustManager)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun manager() = TofuTrustManager(context, hostId)

    private fun fakeCert(bytes: ByteArray = byteArrayOf(1, 2, 3, 4)): X509Certificate {
        val cert = mockk<X509Certificate>()
        every { cert.encoded } returns bytes
        return cert
    }

    private fun expectedPin(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(":") { "%02X".format(it) }

    // ── checkServerTrusted: empty chain ─────────────────────────────────

    @Test
    fun `checkServerTrusted throws CertificateException for empty chain`() {
        val tm = manager()
        val ex = assertThrows(CertificateException::class.java) {
            tm.checkServerTrusted(emptyArray(), "RSA")
        }
        assertTrue(ex.message!!.contains("Empty certificate chain"))
    }

    // ── checkServerTrusted: first-time trust (no stored pin) ────────────

    @Test
    fun `checkServerTrusted saves pin on first successful validation (TOFU)`() {
        every { systemTrustManager.checkServerTrusted(any(), any()) } answers { }
        val cert = fakeCert()
        val tm = manager()

        tm.checkServerTrusted(arrayOf(cert), "RSA")

        assertEquals(expectedPin(cert.encoded), tm.currentPin())
        verify { systemTrustManager.checkServerTrusted(arrayOf(cert), "RSA") }
    }

    @Test
    fun `checkServerTrusted saves pin even when system validation fails on first contact`() {
        every {
            systemTrustManager.checkServerTrusted(any(), any())
        } throws CertificateException("self-signed")
        val cert = fakeCert(byteArrayOf(9, 9, 9))
        val tm = manager()

        // Should not throw — TOFU trusts on first contact regardless of system validation.
        tm.checkServerTrusted(arrayOf(cert), "RSA")

        assertEquals(expectedPin(cert.encoded), tm.currentPin())
    }

    // ── checkServerTrusted: matching stored pin ─────────────────────────

    @Test
    fun `checkServerTrusted succeeds when incoming cert matches stored pin`() {
        every { systemTrustManager.checkServerTrusted(any(), any()) } answers { }
        val cert = fakeCert(byteArrayOf(5, 5, 5))
        val tm = manager()

        // First contact establishes the pin.
        tm.checkServerTrusted(arrayOf(cert), "RSA")
        // Second contact with the same cert should just pass through.
        tm.checkServerTrusted(arrayOf(cert), "RSA")

        verify(exactly = 2) { systemTrustManager.checkServerTrusted(arrayOf(cert), "RSA") }
    }

    @Test
    fun `checkServerTrusted does not throw even if system validation fails when pin matches`() {
        val cert = fakeCert(byteArrayOf(7, 7, 7))
        every {
            systemTrustManager.checkServerTrusted(arrayOf(cert), "RSA")
        } throws CertificateException("expired") andThenThrows CertificateException("expired")

        val tm = manager()
        // First call: system validation throws, but pin is saved via onFailure path.
        tm.checkServerTrusted(arrayOf(cert), "RSA")
        assertEquals(expectedPin(cert.encoded), tm.currentPin())

        // Second call with same cert: pin matches -> runCatching swallows the exception, no throw.
        tm.checkServerTrusted(arrayOf(cert), "RSA")
    }

    // ── checkServerTrusted: pin mismatch ────────────────────────────────

    @Test
    fun `checkServerTrusted throws fingerprint mismatch for different cert`() {
        every { systemTrustManager.checkServerTrusted(any(), any()) } answers { }
        val firstCert = fakeCert(byteArrayOf(1, 1, 1))
        val secondCert = fakeCert(byteArrayOf(2, 2, 2))
        val tm = manager()

        tm.checkServerTrusted(arrayOf(firstCert), "RSA")

        val ex = assertThrows(CertificateException::class.java) {
            tm.checkServerTrusted(arrayOf(secondCert), "RSA")
        }
        assertTrue(ex.message!!.contains("Certificate fingerprint mismatch"))
        assertTrue(ex.message!!.contains(hostId))
    }

    // ── checkClientTrusted delegates to system trust manager ───────────

    @Test
    fun `checkClientTrusted delegates to system trust manager`() {
        every { systemTrustManager.checkClientTrusted(any(), any()) } answers { }
        val cert = fakeCert()
        val tm = manager()

        tm.checkClientTrusted(arrayOf(cert), "RSA")

        verify { systemTrustManager.checkClientTrusted(arrayOf(cert), "RSA") }
    }

    @Test
    fun `checkClientTrusted propagates exception from system trust manager`() {
        every {
            systemTrustManager.checkClientTrusted(any(), any())
        } throws CertificateException("untrusted client")
        val tm = manager()

        assertThrows(CertificateException::class.java) {
            tm.checkClientTrusted(arrayOf(fakeCert()), "RSA")
        }
    }

    // ── getAcceptedIssuers ───────────────────────────────────────────────

    @Test
    fun `getAcceptedIssuers returns issuers from system trust manager`() {
        val issuers = arrayOf(fakeCert())
        every { systemTrustManager.acceptedIssuers } returns issuers
        val tm = manager()

        assertArrayEquals(issuers, tm.acceptedIssuers)
    }

    // ── clearPin / currentPin ────────────────────────────────────────────

    @Test
    fun `currentPin returns null when no pin stored`() {
        val tm = manager()
        assertNull(tm.currentPin())
    }

    @Test
    fun `currentPin returns null when stored value is corrupted (bad format)`() {
        pinsMap["pin_$hostId"] = "not-a-valid-encrypted-value"
        val tm = manager()
        assertNull(tm.currentPin())
    }

    @Test
    fun `clearPin removes stored pin so next check is treated as first contact`() {
        every { systemTrustManager.checkServerTrusted(any(), any()) } answers { }
        val cert = fakeCert(byteArrayOf(3, 3, 3))
        val tm = manager()

        tm.checkServerTrusted(arrayOf(cert), "RSA")
        assertEquals(expectedPin(cert.encoded), tm.currentPin())

        tm.clearPin()
        assertNull(tm.currentPin())

        // A different cert now succeeds again as a fresh TOFU pinning.
        val newCert = fakeCert(byteArrayOf(4, 4, 4))
        tm.checkServerTrusted(arrayOf(newCert), "RSA")
        assertEquals(expectedPin(newCert.encoded), tm.currentPin())
    }
}
