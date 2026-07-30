package com.rite.pillcounting.core.hl7.imageWebService

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rite.pillcounting.core.hl7.mllp.tls.TlsImageKeystoreUtil
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDetailsDao
import com.rite.pillcounting.core.room.models.PillCountTxnDetailsEntity
import com.rite.pillcounting.core.room.models.PillCountTxnEntity
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.scanning.domain.model.BottleInfo
import com.rite.pillcounting.core.scanning.domain.model.BottleInfoJson
import dagger.hilt.EntryPoints
import fi.iki.elonen.NanoHTTPD
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Unit tests for [ImageNanoServer].
 *
 * Robolectric is required because the class extends [NanoHTTPD], touches [Context] file
 * directories, and uses [android.util.Base64]. The Hilt [EntryPoints] lookup performed in
 * `init {}` is intercepted with a static mock so the server can be constructed without a real
 * Hilt component graph, backed by MockK relaxed DAOs. [TlsImageKeystoreUtil] is mocked as an
 * object since its real implementation depends on AndroidKeyStore, which is unavailable under
 * Robolectric (out of scope here — would need an instrumented test). The AES/Keystore-backed
 * decrypt branch of `ImageCrypto` is likewise out of scope; only the non-encrypted (plain bytes)
 * branch is exercised, per repo convention for AndroidKeyStore-dependent code.
 */
@RunWith(RobolectricTestRunner::class)
class ImageNanoServerTest {

    private lateinit var context: Context
    private lateinit var txnDao: PillCountTxnDao
    private lateinit var txnDetailsDao: PillCountTxnDetailsDao
    private lateinit var server: ImageNanoServer

    private fun newTxn(
        txnId: Long = 1L,
        barcodeImage: String? = null,
    ) = PillCountTxnEntity(
        txnId = txnId,
        isDispense = true,
        status = CountStatus.COMPLETED,
        bottleInfoListJson = barcodeImage?.let {
            BottleInfoJson.encode(listOf(BottleInfo(txnId = txnId, barcodeImagePath = it)))
        },
    )

    private fun newDetail(
        txnDetailsId: Long = 1L,
        txnId: Long? = 1L,
        pillCount: Int? = 5,
        imagePath: String? = null,
        type: String? = "VIAL",
        isDeleted: Boolean = false,
        createdAt: Long = System.currentTimeMillis(),
    ) = PillCountTxnDetailsEntity(
        txnDetailsId = txnDetailsId,
        txnId = txnId,
        pillCount = pillCount,
        imagePath = imagePath,
        type = type,
        isDeleted = isDeleted,
        createdAt = createdAt,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        txnDao = mockk(relaxed = true)
        txnDetailsDao = mockk(relaxed = true)

        val entryPoint = mockk<ImageNanoServer.ImageServerDaoEntryPoint>(relaxed = true)
        every { entryPoint.pillCountTxnDao() } returns txnDao
        every { entryPoint.pillCountTxnDetailsDao() } returns txnDetailsDao

        mockkStatic(EntryPoints::class)
        every {
            EntryPoints.get(any(), ImageNanoServer.ImageServerDaoEntryPoint::class.java)
        } returns entryPoint

        mockkObject(TlsImageKeystoreUtil)
        every { TlsImageKeystoreUtil.fingerprint(any()) } returns "AA:BB:CC:DD"

        server = ImageNanoServer(context, 0, null)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ----------------------------------------------------------------
    // Routing
    // ----------------------------------------------------------------

    @Test
    fun `health endpoint returns ok status`() {
        val response = serve("/health")

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        val json = JSONObject(bodyOf(response))
        assertEquals("ok", json.getString("status"))
    }

    @Test
    fun `fingerprint endpoint returns fingerprint from TlsImageKeystoreUtil`() {
        val response = serve("/fingerprint")

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        val json = JSONObject(bodyOf(response))
        assertEquals("AA:BB:CC:DD", json.getString("fingerprint"))
    }

    @Test
    fun `unknown route returns 404`() {
        val response = serve("/some/unknown/route")

        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, response.status)
        val json = JSONObject(bodyOf(response))
        assertFalse(json.getBoolean("success"))
    }

    // ----------------------------------------------------------------
    // getby* endpoints — txn not found
    // ----------------------------------------------------------------

    @Test
    fun `getbymessagecontrolid returns 404 when txn missing`() {
        coEvery { txnDao.getByMessageControlId("MCID-1") } returns null

        val response = serve("/images/getbymessagecontrolid/MCID-1")

        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, response.status)
        assertTrue(JSONObject(bodyOf(response)).getString("error").contains("Transaction not found"))
    }

    @Test
    fun `getbysequencenumber returns 404 when txn missing`() {
        coEvery { txnDao.getBySequenceNumber("SEQ-1") } returns null

        val response = serve("/images/getbysequencenumber/SEQ-1")

        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, response.status)
    }

    @Test
    fun `getbytransactionorderid returns 404 when txn missing`() {
        coEvery { txnDao.getByTransactionOrderId("ORD-1") } returns null

        val response = serve("/images/getbytransactionorderid/ORD-1")

        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, response.status)
    }

    @Test
    fun `getbyrxnumber with rx and fillno routes to getByRxNoAndFillNo`() {
        coEvery { txnDao.getByRxNoAndFillNo("RX1", "F1") } returns null

        val response = serve("/images/getbyrxnumber/RX1/F1")

        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, response.status)
        coEvery { txnDao.getByRxNoAndFillNo("RX1", "F1") }
    }

    @Test
    fun `getbyrxnumber without fillno routes to getMostRecentByRxNo`() {
        coEvery { txnDao.getMostRecentByRxNo("RX1") } returns null

        val response = serve("/images/getbyrxnumber/RX1")

        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, response.status)
    }

    @Test
    fun `getbymessagecontrolid url-decodes the path segment`() {
        coEvery { txnDao.getByMessageControlId("A B") } returns null

        val response = serve("/images/getbymessagecontrolid/A%20B")

        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, response.status)
    }

    // ----------------------------------------------------------------
    // getby* endpoints — no images / DAO exception
    // ----------------------------------------------------------------

    @Test
    fun `getbymessagecontrolid returns 404 when txn has no images`() {
        val txn = newTxn(barcodeImage = null)
        coEvery { txnDao.getByMessageControlId("MCID-1") } returns txn
        coEvery { txnDetailsDao.getAllForTxn(txn.txnId.toString()) } returns emptyList()

        val response = serve("/images/getbymessagecontrolid/MCID-1")

        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, response.status)
        assertTrue(JSONObject(bodyOf(response)).getString("error").contains("No images found"))
    }

    @Test
    fun `getbymessagecontrolid returns 500 when DAO throws`() {
        coEvery { txnDao.getByMessageControlId("MCID-1") } throws RuntimeException("boom")

        val response = serve("/images/getbymessagecontrolid/MCID-1")

        assertEquals(NanoHTTPD.Response.Status.INTERNAL_ERROR, response.status)
    }

    // ----------------------------------------------------------------
    // Zip building
    // ----------------------------------------------------------------

    @Test
    fun `zip contains barcode image named with BARCODE label`(): Unit {
        val barcodeFile = writeTempImage("barcode.jpg", byteArrayOf(1, 2, 3))
        val txn = newTxn(barcodeImage = barcodeFile.absolutePath)
        coEvery { txnDao.getByMessageControlId("MCID-1") } returns txn
        coEvery { txnDetailsDao.getAllForTxn(txn.txnId.toString()) } returns emptyList()

        val response = serve("/images/getbymessagecontrolid/MCID-1")

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        val names = zipEntryNames(response)
        assertEquals(1, names.size)
        assertTrue(names[0].contains("barcode"))
        assertTrue(names[0].startsWith("1_rx_"))
    }

    @Test
    fun `zip skips soft-deleted detail images`() {
        val kept = newDetail(txnDetailsId = 1, imagePath = writeTempImage("kept.jpg").absolutePath, isDeleted = false)
        val deleted = newDetail(txnDetailsId = 2, imagePath = writeTempImage("deleted.jpg").absolutePath, isDeleted = true)
        val txn = newTxn()
        coEvery { txnDao.getBySequenceNumber("SEQ-1") } returns txn
        coEvery { txnDetailsDao.getAllForTxn(txn.txnId.toString()) } returns listOf(kept, deleted)

        val response = serve("/images/getbysequencenumber/SEQ-1")

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals(1, zipEntryNames(response).size)
    }

    @Test
    fun `zip skips blank and null image paths`() {
        val blank = newDetail(txnDetailsId = 1, imagePath = "  ")
        val nullPath = newDetail(txnDetailsId = 2, imagePath = null)
        val valid = newDetail(txnDetailsId = 3, imagePath = writeTempImage("valid.jpg").absolutePath)
        val txn = newTxn()
        coEvery { txnDao.getBySequenceNumber("SEQ-1") } returns txn
        coEvery { txnDetailsDao.getAllForTxn(txn.txnId.toString()) } returns listOf(blank, nullPath, valid)

        val response = serve("/images/getbysequencenumber/SEQ-1")

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals(1, zipEntryNames(response).size)
    }

    @Test
    fun `zip skips details whose image file cannot be resolved`() {
        val unresolvable = newDetail(txnDetailsId = 1, imagePath = "/no/such/file_${System.nanoTime()}.jpg")
        val valid = newDetail(txnDetailsId = 2, imagePath = writeTempImage("valid2.jpg").absolutePath)
        val txn = newTxn()
        coEvery { txnDao.getBySequenceNumber("SEQ-1") } returns txn
        coEvery { txnDetailsDao.getAllForTxn(txn.txnId.toString()) } returns listOf(unresolvable, valid)

        val response = serve("/images/getbysequencenumber/SEQ-1")

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals(1, zipEntryNames(response).size)
    }

    @Test
    fun `zip uses IMAGE fallback label when detail type is null`() {
        val detail = newDetail(txnDetailsId = 1, imagePath = writeTempImage("null_type.jpg").absolutePath, type = null)
        val txn = newTxn()
        coEvery { txnDao.getBySequenceNumber("SEQ-1") } returns txn
        coEvery { txnDetailsDao.getAllForTxn(txn.txnId.toString()) } returns listOf(detail)

        val response = serve("/images/getbysequencenumber/SEQ-1")

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        val names = zipEntryNames(response)
        assertEquals(1, names.size)
        assertTrue(names[0].lowercase().contains("image"))
    }

    @Test
    fun `zip numbers batches per label and overall sequence across entries`() {
        val d1 = newDetail(txnDetailsId = 1, imagePath = writeTempImage("d1.jpg").absolutePath, type = "CONTAINER_PENDING", createdAt = 1)
        val d2 = newDetail(txnDetailsId = 2, imagePath = writeTempImage("d2.jpg").absolutePath, type = "CONTAINER_PENDING", createdAt = 2)
        val txn = newTxn()
        coEvery { txnDao.getBySequenceNumber("SEQ-1") } returns txn
        coEvery { txnDetailsDao.getAllForTxn(txn.txnId.toString()) } returns listOf(d1, d2)

        val response = serve("/images/getbysequencenumber/SEQ-1")

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        val names = zipEntryNames(response).sorted()
        assertEquals(2, names.size)
        assertTrue(names.any { it.startsWith("1_") && it.contains("1B2") })
        assertTrue(names.any { it.startsWith("2_") && it.contains("2B2") })
    }

    // ----------------------------------------------------------------
    // /images/ file endpoint
    // ----------------------------------------------------------------

    @Test
    fun `image endpoint rejects path traversal`() {
        val response = serve("/images/../secret.jpg")

        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
    }

    @Test
    fun `image endpoint rejects blank filename`() {
        val response = serve("/images/")

        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
    }

    @Test
    fun `image endpoint rejects filename containing slash`() {
        val response = serve("/images/sub/dir/file.jpg")

        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
    }

    @Test
    fun `image endpoint returns 404 when file missing`() {
        val response = serve("/images/does_not_exist_${System.nanoTime()}.jpg")

        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, response.status)
    }

    @Test
    fun `image endpoint returns base64 json on success`() {
        val bytes = byteArrayOf(9, 8, 7, 6)
        val file = writeTempImage("found_${System.nanoTime()}.jpg", bytes)

        val response = serve("/images/${file.name}")

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        val json = JSONObject(bodyOf(response))
        assertTrue(json.getBoolean("success"))
        assertEquals(file.name, json.getString("file"))
        assertNotNull(json.getString("base64"))
        val decoded = android.util.Base64.decode(json.getString("base64"), android.util.Base64.NO_WRAP)
        assertTrue(decoded.contentEquals(bytes))
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private fun serve(uri: String): NanoHTTPD.Response {
        val session = mockk<NanoHTTPD.IHTTPSession>(relaxed = true)
        every { session.uri } returns uri
        every { session.method } returns NanoHTTPD.Method.GET
        return server.serve(session)
    }

    private fun bodyOf(response: NanoHTTPD.Response): String {
        val stream = response.data ?: return ""
        return stream.readBytes().toString(Charsets.UTF_8)
    }

    private fun zipEntryNames(response: NanoHTTPD.Response): List<String> {
        val bytes = response.data.readBytes()
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                names.add(entry.name)
                entry = zis.nextEntry
            }
        }
        return names
    }

    private fun writeTempImage(name: String, bytes: ByteArray = byteArrayOf(1, 2, 3)): java.io.File {
        val dir = context.cacheDir
        dir.mkdirs()
        val file = java.io.File(dir, name)
        file.writeBytes(bytes)
        return file
    }
}
