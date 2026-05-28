package com.rite.pillcounting.core.hl7.imageWebService

import android.content.Context
import android.util.Base64
import android.util.Log
import com.rite.pillcounting.core.hl7.mllp.tls.TlsImageKeystoreUtil
import com.rite.pillcounting.core.hl7.imageWebService.TlsImageKeystoreUtil
import com.rite.pillcounting.core.security.ImageCrypto
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import javax.net.ssl.SSLServerSocketFactory

class ImageNanoServer(
    private val context: Context,
    port: Int,
    sslFactory: SSLServerSocketFactory
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "ImageNanoServer"
    }

    init {
        // Attach the SSL factory — this makes NanoHTTPD use HTTPS
        makeSecure(sslFactory, null)
        setTempFileManagerFactory { PrivateTempFileManager(context) }
    }

    override fun serve(session: IHTTPSession): Response {
        Log.d(TAG, "Request: ${session.method} ${session.uri}")

        return when {
            session.uri == "/health"      -> handleHealth()
            session.uri == "/fingerprint" -> handleFingerprint()
            session.uri.startsWith("/images/") -> handleImage(
                session.uri.removePrefix("/images/")
            )
            else -> errorResponse(
                status = Response.Status.NOT_FOUND,
                message = "Endpoint not found"
            )
        }
    }

    // ----------------------------------------------------------------
    // Handlers
    // ----------------------------------------------------------------

    private fun handleHealth(): Response =
        jsonResponse("""{"status":"ok"}""")

    private fun handleFingerprint(): Response {
        val fp = TlsImageKeystoreUtil.fingerprint(context)
        return jsonResponse(
            JSONObject().put("fingerprint", fp).toString()
        )
    }

    private fun handleImage(fileName: String): Response {
        // 1. Validate filename — block path traversal attacks
        if (!isSafeFileName(fileName)) {
            return errorResponse(
                status = Response.Status.BAD_REQUEST,
                message = "Invalid file name"
            )
        }

        // 2. Search across all storage roots
        val searchRoots = listOfNotNull(
            context.filesDir,
            context.cacheDir,
            context.getExternalFilesDir(null),
            context.getExternalFilesDir("Pictures")
        )

        val imageFile = searchRoots
            .flatMap { it.walkTopDown().toList() }
            .firstOrNull { it.name == fileName }

        // 3. Return 404 if not found
        if (imageFile == null || !imageFile.exists()) {
            return errorResponse(
                status = Response.Status.NOT_FOUND,
                message = "Image not found: $fileName"
            )
        }

        // 4. Read, decrypt if needed, and return as base64 JSON
        return try {
            val raw = imageFile.readBytes()
            val bytes = if (ImageCrypto.isEncrypted(raw)) ImageCrypto.decrypt(raw) else raw
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

            jsonResponse(
                JSONObject()
                    .put("success", true)
                    .put("file", fileName)
                    .put("base64", base64)
                    .toString()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read image: $fileName", e)
            errorResponse(
                status = Response.Status.INTERNAL_ERROR,
                message = "Failed to read image"
            )
        }
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private fun isSafeFileName(name: String): Boolean =
        name.isNotBlank() &&
                !name.contains("..") &&
                !name.contains("/") &&
                !name.contains("\\")

    private fun jsonResponse(json: String): Response =
        newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            json
        )

    private fun errorResponse(
        status: Response.Status,
        message: String
    ): Response =
        newFixedLengthResponse(
            status,
            "application/json",
            JSONObject()
                .put("success", false)
                .put("error", message)
                .toString()
        )
}