package com.rite.pillcounting.core.security

/**
 * AES-256-GCM image encryption backed by the Android Keystore, via the shared
 * [KeystoreAesGcm] primitives (same key infra as the DB's DEK wrapping).
 *
 * File format on disk:
 *   [ MAGIC (4 bytes "RENC") ][ IV (12 bytes) ][ AES-GCM ciphertext + 16-byte auth tag ]
 *
 * The MAGIC prefix lets [isEncrypted] distinguish new encrypted files from
 * legacy plain JPEG files so both can be decoded transparently during migration.
 */
object ImageCrypto {

    private const val KEY_ALIAS = "pill_image_enc_key"
    private const val IV_LEN = 12
    private val MAGIC = "RENC".toByteArray(Charsets.US_ASCII)

    /** Encrypts raw image bytes and prepends MAGIC to the wrapped (IV + ciphertext) output. */
    fun encrypt(bytes: ByteArray): ByteArray = MAGIC + KeystoreAesGcm.wrap(KEY_ALIAS, bytes)

    /** Decrypts bytes previously produced by [encrypt]. */
    fun decrypt(bytes: ByteArray): ByteArray {
        require(bytes.size > MAGIC.size + IV_LEN) { "Invalid encrypted image data" }
        return KeystoreAesGcm.unwrap(KEY_ALIAS, bytes.copyOfRange(MAGIC.size, bytes.size))
    }

    /** Returns true if [bytes] starts with the RENC magic header. */
    fun isEncrypted(bytes: ByteArray): Boolean =
        bytes.size > MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)
}
