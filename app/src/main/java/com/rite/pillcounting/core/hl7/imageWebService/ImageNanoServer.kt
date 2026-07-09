package com.rite.pillcounting.core.hl7.imageWebService

import android.content.Context
import android.util.Base64
import com.rite.pillcounting.core.hl7.mllp.tls.TlsImageKeystoreUtil
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDetailsDao
import com.rite.pillcounting.core.room.models.PillCountTxnDetailsEntity
import com.rite.pillcounting.core.room.models.PillCountTxnEntity
import com.rite.pillcounting.core.security.ImageCrypto
import com.rite.pillcounting.core.utils.logger.AppLogger
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.net.ssl.SSLServerSocketFactory

class ImageNanoServer(
    private val context: Context,
    port: Int,
    sslFactory: SSLServerSocketFactory?
) : NanoHTTPD(port) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ImageServerDaoEntryPoint {
        fun pillCountTxnDao(): PillCountTxnDao
        fun pillCountTxnDetailsDao(): PillCountTxnDetailsDao
    }

    private val logger = AppLogger("ImageNanoServer")

    private val txnDao: PillCountTxnDao
    private val txnDetailsDao: PillCountTxnDetailsDao

    init {
        // Attach the SSL factory — this makes NanoHTTPD use HTTPS.
        // When null (TLS bypassed), NanoHTTPD serves plain HTTP.
        if (sslFactory != null) makeSecure(sslFactory, null)
        setTempFileManagerFactory { PrivateTempFileManager(context) }

        val entryPoint = EntryPoints.get(
            context.applicationContext,
            ImageServerDaoEntryPoint::class.java
        )
        txnDao = entryPoint.pillCountTxnDao()
        txnDetailsDao = entryPoint.pillCountTxnDetailsDao()
    }

    override fun serve(session: IHTTPSession): Response {
        logger.d("Request: ${session.method} ${session.uri}")

        val segments = session.uri.trim('/').split("/")

        return when {
            session.uri == "/health"      -> handleHealth()
            session.uri == "/fingerprint" -> handleFingerprint()

            segments.size == 3 && segments[0] == "images" && segments[1] == "getbymessagecontrolid" ->
                handleTxnLookup { txnDao.getByMessageControlId(decode(segments[2])) }

            segments.size == 3 && segments[0] == "images" && segments[1] == "getbysequencenumber" ->
                handleTxnLookup { txnDao.getBySequenceNumber(decode(segments[2])) }

            segments.size == 3 && segments[0] == "images" && segments[1] == "getbytransactionorderid" ->
                handleTxnLookup { txnDao.getByTransactionOrderId(decode(segments[2])) }

            segments.size == 4 && segments[0] == "images" && segments[1] == "getbyrxnumber" ->
                handleTxnLookup { txnDao.getByRxNoAndFillNo(decode(segments[2]), decode(segments[3])) }

            segments.size == 3 && segments[0] == "images" && segments[1] == "getbyrxnumber" ->
                handleTxnLookup { txnDao.getMostRecentByRxNo(decode(segments[2])) }

            session.uri.startsWith("/images/") -> handleImage(
                session.uri.removePrefix("/images/")
            )
            else -> errorResponse(
                status = Response.Status.NOT_FOUND,
                message = "Endpoint not found"
            )
        }
    }

    private fun decode(segment: String): String =
        URLDecoder.decode(segment, "UTF-8")

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
            logger.e("Failed to read image: $fileName", e)
            errorResponse(
                status = Response.Status.INTERNAL_ERROR,
                message = "Failed to read image"
            )
        }
    }

    // ----------------------------------------------------------------
    // Transaction image-zip lookups (getby* endpoints)
    // ----------------------------------------------------------------

    private fun handleTxnLookup(lookup: suspend () -> PillCountTxnEntity?): Response {
        return try {
            val txn = runBlocking { lookup() }
                ?: return errorResponse(Response.Status.NOT_FOUND, "Transaction not found")

            val entries = runBlocking { collectImageEntries(txn) }
            if (entries.isEmpty()) {
                return errorResponse(Response.Status.NOT_FOUND, "No images found for transaction")
            }

            zipResponse(entries)
        } catch (e: Exception) {
            logger.e("Transaction image lookup failed", e)
            errorResponse(Response.Status.INTERNAL_ERROR, "Failed to build image archive")
        }
    }

    /** One image, resolved to bytes, with the naming metadata needed for the zip entry. */
    private data class ImageEntry(val type: String, val pillCount: Int, val file: File)

    private suspend fun collectImageEntries(txn: PillCountTxnEntity): List<ImageEntry> {
        val entries = mutableListOf<ImageEntry>()

        txn.barcodeImage?.takeIf { it.isNotBlank() }?.let { path ->
            resolveFile(path)?.let { entries.add(ImageEntry("BARCODE", 0, it)) }
        }

        val details = txnDetailsDao.getAllForTxn(txn.txnId.toString())
            .filter { !it.isDeleted }
            .sortedBy { it.createdAt }

        for (detail: PillCountTxnDetailsEntity in details) {
            val path = detail.imagePath?.takeIf { it.isNotBlank() } ?: continue
            val file = resolveFile(path) ?: continue
            entries.add(ImageEntry(detail.type ?: "IMAGE", detail.pillCount ?: 0, file))
        }

        return entries
    }

    private fun zipResponse(entries: List<ImageEntry>): Response {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            entries.forEachIndexed { index, entry ->
                val sequence = index + 1
                val ext = entry.file.extension.ifBlank { "jpg" }
                zip.putNextEntry(ZipEntry("${entry.type}_${sequence}_${entry.pillCount}.$ext"))
                zip.write(readImageBytes(entry.file))
                zip.closeEntry()
            }
        }
        val bytes = baos.toByteArray()
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/zip",
            bytes.inputStream(),
            bytes.size.toLong()
        )
    }

    private fun readImageBytes(file: File): ByteArray {
        val raw = file.readBytes()
        return if (ImageCrypto.isEncrypted(raw)) ImageCrypto.decrypt(raw) else raw
    }

    /** Resolves a stored image path — direct hit first, else a name search across storage roots. */
    private fun resolveFile(pathOrName: String): File? {
        val direct = File(pathOrName)
        if (direct.exists()) return direct

        val searchRoots = listOfNotNull(
            context.filesDir,
            context.cacheDir,
            context.getExternalFilesDir(null),
            context.getExternalFilesDir("Pictures")
        )
        val targetName = direct.name
        return searchRoots
            .flatMap { it.walkTopDown().toList() }
            .firstOrNull { it.name == targetName }
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