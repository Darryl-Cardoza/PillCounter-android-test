package com.rite.pillcounting.core.scanning.data

import android.content.Context
import com.rite.pillcounting.core.utils.logger.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads drug images (`.webp`) from the API and persists them to local internal storage.
 *
 * ### Storage location
 * `<filesDir>/drug_images/<sanitized_drug_name>.webp`
 *
 * The file is named after the drug name (lowercased, spaces/special-chars replaced with `_`)
 * plus the original extension derived from the URL (default `.webp`).
 *
 * ### Caching
 * If the target file already exists its path is returned immediately without a network request.
 *
 * ### Thread safety
 * All I/O runs on [Dispatchers.IO]; this function is safe to call from any coroutine scope.
 */
@Singleton
class DrugImageDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {

    private val logger = AppLogger.create<DrugImageDownloader>()

    /**
     * Downloads [url] and saves it locally under a filename derived from [drugName].
     *
     * @param url       The remote image URL (expected `.webp`).
     * @param drugName  Human-readable drug name used to build the local filename
     *                  (e.g. `"ETODOLAC 400MG TABLET"` → `etodolac_400mg_tablet.webp`).
     * @return Absolute path of the saved file, or `null` if the download failed.
     */
    suspend fun downloadAndSave(url: String?, drugName: String?): String? {
        if (url.isNullOrBlank() || drugName.isNullOrBlank()) return null

        return withContext(Dispatchers.IO) {
            try {
                val ext = url.substringAfterLast('.', "webp").lowercase()
                    .take(10) // guard against malformed URLs
                val safeFileName = drugName
                    .lowercase()
                    .replace(Regex("[^a-z0-9]+"), "_")
                    .trim('_')
                    .take(100) + ".$ext"

                val dir = File(context.filesDir, DRUG_IMAGES_DIR).also { it.mkdirs() }
                val targetFile = File(dir, safeFileName)

                if (targetFile.exists() && targetFile.length() > 0) {
                    logger.d("DrugImage cache-hit: ${targetFile.absolutePath}")
                    return@withContext targetFile.absolutePath
                }

                val request = Request.Builder().url(url).build()
                val response = okHttpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    logger.w("DrugImage download failed: HTTP ${response.code} for $url")
                    return@withContext null
                }

                response.body?.byteStream()?.use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: run {
                    logger.w("DrugImage response body was null for $url")
                    return@withContext null
                }

                logger.i("DrugImage saved: ${targetFile.absolutePath}")
                targetFile.absolutePath
            } catch (e: Exception) {
                logger.e("DrugImage download exception for url=$url drugName=$drugName", e)
                null
            }
        }
    }

    companion object {
        private const val DRUG_IMAGES_DIR = "drug_images"
    }
}
