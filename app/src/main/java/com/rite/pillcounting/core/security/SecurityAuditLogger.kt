package com.rite.pillcounting.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Tamper-evident encrypted audit log for HIPAA §164.312(b) compliance.
 *
 * - AES-256-GCM via Android Keystore (hardware-backed on supported devices)
 * - Each write prepends a 12-byte IV to the ciphertext
 * - Format per line: timestamp|checkName|PASS/FAIL|detail
 * - Rotates when log exceeds MAX_LOG_SIZE_BYTES
 * - Never throws to the caller
 */
object SecurityAuditLogger {

    private const val KEY_ALIAS          = "rite_security_audit_key"
    private const val KEYSTORE_PROVIDER  = "AndroidKeyStore"
    private const val TRANSFORMATION     = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH     = 128
    private const val IV_SIZE_BYTES      = 12
    private const val LOG_FILE_NAME      = "security_audit.log"
    private const val ROTATED_FILE_NAME  = "security_audit_prev.log"
    private const val MAX_LOG_SIZE_BYTES = 512 * 1024 // 512 KB

    // ─── Public API ───────────────────────────────────────────────────────────

    fun log(context: Context, event: AuditEvent) {
        try {
            rotateIfNeeded(context)
            val existing = readDecrypted(context) ?: ""
            writeEncrypted(context, existing + event.toLogLine())
        } catch (_: Exception) {}
    }

    fun logAll(context: Context, events: List<AuditEvent>) {
        try {
            rotateIfNeeded(context)
            val existing = readDecrypted(context) ?: ""
            val appended = existing + events.joinToString("") { it.toLogLine() }
            writeEncrypted(context, appended)
        } catch (_: Exception) {}
    }

    fun readAll(context: Context): List<AuditEvent> {
        return try {
            (readDecrypted(context) ?: "")
                .lines()
                .filter { it.isNotBlank() }
                .mapNotNull { AuditEvent.fromLogLine(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ─── Crypto ───────────────────────────────────────────────────────────────

    private fun writeEncrypted(context: Context, plaintext: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).also {
            it.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val iv         = cipher.iv                          // 12 bytes, Keystore-generated
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // File layout: [12-byte IV][ciphertext]
        logFile(context).writeBytes(iv + ciphertext)
    }

    private fun readDecrypted(context: Context): String? {
        val file = logFile(context)
        if (!file.exists()) return null

        val raw = file.readBytes()
        if (raw.size <= IV_SIZE_BYTES) return null

        val iv         = raw.copyOfRange(0, IV_SIZE_BYTES)
        val ciphertext = raw.copyOfRange(IV_SIZE_BYTES, raw.size)

        val cipher = Cipher.getInstance(TRANSFORMATION).also {
            it.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        }

        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    // ─── Keystore ─────────────────────────────────────────────────────────────

    private fun getOrCreateKey(): SecretKey {
        val keystore = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }

        // Return existing key if present
        keystore.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }

        // Generate a new AES-256-GCM key — hardware-backed where available
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).also {
            it.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false) // set true if you want biometric lock
                    .build()
            )
            return it.generateKey()
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun rotateIfNeeded(context: Context) {
        val file = logFile(context)
        if (file.exists() && file.length() > MAX_LOG_SIZE_BYTES) {
            logFile(context, ROTATED_FILE_NAME).delete()
            file.renameTo(logFile(context, ROTATED_FILE_NAME))
        }
    }

    private fun logFile(context: Context, name: String = LOG_FILE_NAME) =
        File(context.applicationContext.filesDir, name)
}
