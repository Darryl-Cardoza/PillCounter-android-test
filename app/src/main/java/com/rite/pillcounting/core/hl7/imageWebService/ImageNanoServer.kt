package com.rite.pillcounting.core.hl7.imageWebService

import android.content.Context
import android.util.Base64
import com.rite.pillcounting.core.hl7.mllp.tls.TlsImageKeystoreUtil
import com.rite.pillcounting.core.models.toImageLabel
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDetailsDao
import com.rite.pillcounting.core.room.models.PillCountTxnDetailsEntity
import com.rite.pillcounting.core.room.models.PillCountTxnEntity
import com.rite.pillcounting.core.scanning.domain.model.BottleInfoJson
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32
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
        logger.d("Request: ${session.method} ${session.uri}${queryStringOf(session)}")

        val rawUri = session.uri.trim('/')

        // PMS sends query params with no leading "?", e.g. "/pic=*&format=zip&orderId=...&Last".
        // Treat everything after the leading "/" as the query string when it contains "=";
        // otherwise fall back to NanoHTTPD's own path/query split (proper "?query" form).
        val (pathPart, queryPart) = if (rawUri.contains("=")) {
            "" to rawUri
        } else {
            rawUri to (session.queryParameterString ?: "")
        }
        val segments = pathPart.split("/").filter { it.isNotEmpty() }
        val params = if (queryPart.isNotEmpty()) parseQuery(queryPart) else
            session.parameters.mapValues { it.value.firstOrNull().orEmpty() }

        // GET /images?pic=*&format=zip&orderid=<transactionOrderId>
        // GET /?pic=*&format=zip&orderid=<transactionOrderId>[&Last]
        // GET /pic=*&format=zip&orderid=<transactionOrderId>[&Last]   (no leading "?")
        // `pic` accepted but ignored (future: select which images; "*" = all).
        // `Last` (or any other bare flag) is accepted but ignored.
        val isRootOrImages = segments.isEmpty() || (segments.size == 1 && segments[0] == "images")
        val orderId = params["orderid"]
        if (isRootOrImages && params["format"]?.lowercase() == "zip" && !orderId.isNullOrBlank()) {
            return handleTxnLookup(zipFileName = "$orderId.zip", naming = ZipNaming.PMS_FILE_NAMING) {
                txnDao.getByTransactionOrderId(orderId)
            }
        }

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

    private fun queryStringOf(session: IHTTPSession): String =
        if (session.queryParameterString.isNullOrBlank()) "" else "?${session.queryParameterString}"

    private fun decode(segment: String): String =
        URLDecoder.decode(segment, "UTF-8")

    /** Parses a raw query string (`a=1&b=2`) into a lowercase-keyed map. Later duplicate keys win. */
    private fun parseQuery(query: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (pair in query.split("&")) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf("=")
            val rawKey = if (idx >= 0) pair.substring(0, idx) else pair
            val rawValue = if (idx >= 0) pair.substring(idx + 1) else ""
            val key = decode(rawKey).lowercase()
            result[key] = decode(rawValue)
        }
        return result
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

    /** Which zip entry naming scheme to use. [LEGACY] is the existing `getby*` route naming
     * (unchanged); [PMS_FILE_NAMING] is the PMS-facing `Rx_ID_YYYY-MM-DD_HH-MM-SS_TTTT#.jpg`
     * convention (aka Eyecon naming) used only by the `pic=*&format=zip&orderid=...` endpoint. */
    private enum class ZipNaming { LEGACY, PMS_FILE_NAMING }

    private fun handleTxnLookup(
        zipFileName: String = "images.zip",
        naming: ZipNaming = ZipNaming.LEGACY,
        lookup: suspend () -> PillCountTxnEntity?
    ): Response {
        return try {
            val txn = runBlocking { lookup() }
                ?: return errorResponse(Response.Status.NOT_FOUND, "Transaction not found")

            val entries = runBlocking { collectImageEntries(txn) }
            if (entries.isEmpty()) {
                return errorResponse(Response.Status.NOT_FOUND, "No images found for transaction")
            }

            zipResponse(
                entries,
                zipFileName = zipFileName,
                naming = naming,
                rxNo = txn.rxNo.orEmpty(),
                orderId = txn.transactionOrderId.orEmpty()
            )
        } catch (e: Exception) {
            logger.e("Transaction image lookup failed", e)
            errorResponse(Response.Status.INTERNAL_ERROR, "Failed to build image archive")
        }
    }

    /** One image, resolved to bytes, with the naming metadata needed for the zip entry. */
    private data class ImageEntry(val type: String, val pillCount: Int, val file: File, val createdAt: Long)

    private suspend fun collectImageEntries(txn: PillCountTxnEntity): List<ImageEntry> {
        val entries = mutableListOf<ImageEntry>()

        BottleInfoJson.decode(txn.bottleInfoListJson)
            .mapNotNull { it.barcodeImagePath?.takeIf { path -> path.isNotBlank() } }
            .forEach { path ->
                resolveFile(path)?.let { entries.add(ImageEntry("BARCODE", 0, it, txn.createdAt)) }
            }

        val details = txnDetailsDao.getAllForTxn(txn.txnId.toString())
            .filter { !it.isDeleted }
            .sortedBy { it.createdAt }

        for (detail: PillCountTxnDetailsEntity in details) {
            val path = detail.imagePath?.takeIf { it.isNotBlank() } ?: continue
            val file = resolveFile(path) ?: continue
            entries.add(ImageEntry(detail.type ?: "IMAGE", detail.pillCount ?: 0, file, detail.createdAt))
        }

        return entries
    }

    /** Maps an internal detail/entry type to its PMS (Eyecon) TTTT code. */
    private fun pmsTypeCode(type: String): String = when (type) {
        "SCAN", "BARCODE" -> "CoVL"
        "CONTAINER_INITIATE", "TARGET_VERIFICATION" -> "BWTP"
        "TARGET_REVERIFICATION" -> "BWDC"
        "VIAL" -> "CoSS"
        "CONTAINER_PENDING" -> "BWBC"
        else -> "BWTP"
    }

    private fun zipResponse(
        entries: List<ImageEntry>,
        zipFileName: String = "images.zip",
        naming: ZipNaming = ZipNaming.LEGACY,
        rxNo: String = "",
        orderId: String = ""
    ): Response {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            when (naming) {
                ZipNaming.LEGACY -> {
                    // Batch position/total is per label: e.g. two CONTAINER_PENDING images are
                    // 1B2/2B2, while a lone VIAL image is 1B1. Overall sequence is the position
                    // across all entries.
                    val labels = entries.map { it.type.toImageLabel() }
                    val batchTotalsByLabel = labels.groupingBy { it }.eachCount()
                    val batchCounters = mutableMapOf<String, Int>()

                    entries.forEachIndexed { index, entry ->
                        val seq = index + 1
                        val label = labels[index]
                        val batchNum = (batchCounters[label] ?: 0) + 1
                        batchCounters[label] = batchNum
                        val batchTotal = batchTotalsByLabel[label] ?: 1
                        val ext = entry.file.extension.ifBlank { "jpg" }
                        zip.putNextEntry(
                            ZipEntry("${seq}_rx_${label}_${batchNum}B${batchTotal}_qty${entry.pillCount}.$ext")
                        )
                        zip.write(readImageBytes(entry.file))
                        zip.closeEntry()
                    }
                }

                ZipNaming.PMS_FILE_NAMING -> {
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                    val codes = entries.map { pmsTypeCode(it.type) }
                    val codeCounters = mutableMapOf<String, Int>()

                    entries.forEachIndexed { index, entry ->
                        val code = codes[index]
                        val number = (codeCounters[code] ?: 0) + 1
                        codeCounters[code] = number
                        val timestamp = dateFormat.format(Date(entry.createdAt))
                        // `entry.file` is the on-disk (encrypted) file — the zip entry holds
                        // already-decrypted jpeg bytes, so always name it `.jpg`.
                        zip.putNextEntry(
                            ZipEntry("${rxNo}_${orderId}_${timestamp}_${code}${number}.jpg")
                        )
                        zip.write(readImageBytes(entry.file))
                        zip.closeEntry()
                    }
                }
            }
        }
        val bytes = baos.toByteArray()
        val crc = CRC32().apply { update(bytes) }.value
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/zip",
            bytes.inputStream(),
            bytes.size.toLong()
        ).apply {
            addHeader("Content-Disposition", "attachment; filename=\"$zipFileName\"")
            addHeader("CRC", crc.toString())
        }
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