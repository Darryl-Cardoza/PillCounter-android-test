package com.rite.pillcounting.core.utils.coil

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.rite.pillcounting.core.security.ImageCrypto
import okio.Buffer
import java.io.File

/**
 * Coil [Fetcher] that transparently decrypts images encrypted with [ImageCrypto].
 *
 * - If the file starts with the RENC magic header it is decrypted before decode.
 * - If decryption fails (e.g. corrupted) it falls back to the raw bytes so the
 *   app does not crash.
 * - Plain JPEG files (legacy, pre-encryption) are passed through unchanged, so
 *   existing stored images continue to display without a migration step.
 */
class EncryptedImageFetcher(
    private val file: File,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val raw = file.readBytes()
        val bytes = if (ImageCrypto.isEncrypted(raw)) {
            try {
                ImageCrypto.decrypt(raw)
            } catch (_: Exception) {
                raw
            }
        } else {
            raw
        }

        val buffer = Buffer().write(bytes)
        return SourceResult(
            source = ImageSource(source = buffer, context = options.context),
            mimeType = "image/jpeg",
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<File> {
        override fun create(data: File, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (data.exists()) EncryptedImageFetcher(data, options) else null
    }
}
