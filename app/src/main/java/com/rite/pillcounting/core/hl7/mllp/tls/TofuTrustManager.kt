package com.rite.pillcounting.core.hl7.mllp.tls

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class TofuTrustManager(
    private val context: Context,
    private val hostIdentifier: String
) : X509TrustManager {

    private val pinKey = "pin_${hostIdentifier}"

    // ── Android's default trust manager — do NOT bypass this ──────────
    private val systemTrustManager: X509TrustManager by lazy {
        val factory = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        )
        factory.init(null as KeyStore?)  // null = use system trust store
        factory.trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()
    }

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "tofu_pins",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun checkClientTrusted(
        chain: Array<X509Certificate>,
        authType: String
    ) {
        // Delegate to system — we are the client, not the server
        systemTrustManager.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String
    ) {
        if (chain.isEmpty()) throw CertificateException("Empty certificate chain")

        val serverCert = chain[0]
        val incomingPin = computePin(serverCert)
        val storedPin = prefs.getString(pinKey, null)

        when {
            storedPin == null -> {
                // First connection — try system validation first.
                // If the PMS server has a CA-signed cert, great.
                // If it's self-signed, system validation throws and we
                // fall through to TOFU pinning below.
                runCatching {
                    systemTrustManager.checkServerTrusted(chain, authType)
                    // System validation passed — also pin it for consistency
                    prefs.edit { putString(pinKey, incomingPin) }
                    Log.i(TAG, "[$hostIdentifier] CA-valid cert pinned: $incomingPin")
                }.onFailure {
                    // System validation failed (expected for self-signed LAN certs)
                    // Accept and pin on first sight — TOFU
                    prefs.edit { putString(pinKey, incomingPin) }
                    Log.i(TAG, "[$hostIdentifier] Self-signed cert pinned via TOFU: $incomingPin")
                }
            }

            storedPin == incomingPin -> {
                // Pin matches — cert is known and trusted
                // Still run system validation if possible, but don't fail if not
                runCatching {
                    systemTrustManager.checkServerTrusted(chain, authType)
                }
                Log.d(TAG, "[$hostIdentifier] Cert matches stored pin ✓")
            }

            else -> {
                // Pin mismatch — reject unconditionally regardless of CA validity
                // This protects against MITM even with a rogue trusted CA
                Log.e(TAG, "[$hostIdentifier] CERT MISMATCH — stored=$storedPin incoming=$incomingPin")
                throw CertificateException(
                    "Certificate fingerprint mismatch for $hostIdentifier. " +
                            "If the server certificate was legitimately rotated, " +
                            "an admin must clear the stored pin before reconnecting."
                )
            }
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        systemTrustManager.acceptedIssuers  // Delegate to system — never return empty

    // ── Helpers ──────────────────────────────────────────────────────

    private fun computePin(cert: X509Certificate): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }
    }

    fun clearPin() {
        prefs.edit { remove(pinKey) }
        Log.w(TAG, "[$hostIdentifier] Pin cleared — will re-pin on next connection")
    }

    fun currentPin(): String? = prefs.getString(pinKey, null)

    companion object {
        private const val TAG = "TofuTrustManager"
    }
}