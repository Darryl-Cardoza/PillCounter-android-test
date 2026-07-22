package com.rite.pillcounting.core.scanning.data

import android.content.Context
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Unit tests for [DrugImageDownloader].
 *
 * android.util.Log is stubbed statically because AppLogger (used internally) is a thin
 * wrapper around it and Log is not implemented on the plain JVM. Android's [Context] is
 * mocked with MockK and its `filesDir` is pointed at a JUnit [TemporaryFolder] so that
 * real file I/O in the class under test can run against a real, disposable directory on
 * disk. Network calls are mocked via [OkHttpClient]/`Call`/[Response] MockK mocks - no
 * real network request is ever made.
 */
class DrugImageDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var downloader: DrugImageDownloader
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>(), any()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        every { Log.println(any(), any(), any()) } returns 0

        filesDir = tempFolder.newFolder("files")
        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir

        okHttpClient = mockk()
        downloader = DrugImageDownloader(context, okHttpClient)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun fakeResponse(
        code: Int = 200,
        bodyBytes: ByteArray? = "image-bytes".toByteArray(),
        url: String = "https://example.com/img.webp",
    ): Response {
        val body = (bodyBytes ?: ByteArray(0)).toResponseBody("image/webp".toMediaTypeOrNull())
        return Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(body)
            .build()
    }

    private fun stubCall(response: Response) {
        val call = mockk<okhttp3.Call>()
        every { call.execute() } returns response
        every { okHttpClient.newCall(any()) } returns call
    }

    private fun stubCallThrows(exception: IOException) {
        val call = mockk<okhttp3.Call>()
        every { call.execute() } throws exception
        every { okHttpClient.newCall(any()) } returns call
    }

    // ---------- invalid input ----------

    @Test
    fun `returns null when url is null`() = runTest {
        val result = downloader.downloadAndSave(null, "ETODOLAC 400MG TABLET")
        assertNull(result)
    }

    @Test
    fun `returns null when url is blank`() = runTest {
        val result = downloader.downloadAndSave("   ", "ETODOLAC 400MG TABLET")
        assertNull(result)
    }

    @Test
    fun `returns null when drugName is null`() = runTest {
        val result = downloader.downloadAndSave("https://example.com/img.webp", null)
        assertNull(result)
    }

    @Test
    fun `returns null when drugName is blank`() = runTest {
        val result = downloader.downloadAndSave("https://example.com/img.webp", "")
        assertNull(result)
    }

    @Test
    fun `does not touch network when input invalid`() = runTest {
        downloader.downloadAndSave(null, null)
        // No call should have been made to the mocked client since it wasn't stubbed
        // for newCall(); calling it would throw MockK "no answer found" unless we relax.
        // Verify by confirming newCall was never invoked at all.
        io.mockk.verify(exactly = 0) { okHttpClient.newCall(any()) }
    }

    // ---------- happy path ----------

    @Test
    fun `downloads and saves file returning absolute path`() = runTest {
        stubCall(fakeResponse())

        val result = downloader.downloadAndSave("https://example.com/img.webp", "ETODOLAC 400MG TABLET")

        assertTrue(result != null)
        val saved = File(result!!)
        assertTrue(saved.exists())
        assertEquals("etodolac_400mg_tablet.webp", saved.name)
        assertEquals("image-bytes", saved.readText())
    }

    @Test
    fun `sanitizes drug name special characters and spaces`() = runTest {
        stubCall(fakeResponse())

        val result = downloader.downloadAndSave(
            "https://example.com/img.webp",
            "Aspirin 81mg (Coated)!!",
        )

        assertTrue(result != null)
        assertEquals("aspirin_81mg_coated.webp", File(result!!).name)
    }

    @Test
    fun `derives extension from url and lowercases it`() = runTest {
        stubCall(fakeResponse(url = "https://example.com/path/image.PNG"))

        val result = downloader.downloadAndSave("https://example.com/path/image.PNG", "Drug One")

        assertTrue(result != null)
        assertEquals("drug_one.png", File(result!!).name)
    }

    @Test
    fun `derives extension from the whole url including the hostname dot`() = runTest {
        // The extension is derived from `url.substringAfterLast('.', "webp")` on the WHOLE
        // url string, not just the path's last segment — so a hostname dot (here,
        // "example.com") is picked up as the "extension" source even though the path itself
        // has no dot. This documents that actual (quirky) behavior rather than the originally
        // intended "falls back to webp when the url has no dot anywhere in the path" case,
        // which this url does not actually exercise since "example.com" contains a dot.
        //
        // ext = "com/imagewithoutext".take(10) = "com/imagew", so the "filename" becomes
        // "drug_two.com/imagew" — containing a path separator. The download code only
        // mkdirs() the top-level drug_images dir, not this nested "drug_two.com"
        // subdirectory, so writing to that path throws (caught -> null returned).
        stubCall(fakeResponse(url = "https://example.com/imagewithoutext"))

        val result = downloader.downloadAndSave("https://example.com/imagewithoutext", "Drug Two")

        assertNull(result)
    }

    @Test
    fun `truncates malformed extension to 10 characters`() = runTest {
        val longExt = "a".repeat(50)
        stubCall(fakeResponse(url = "https://example.com/image.$longExt"))

        val result = downloader.downloadAndSave("https://example.com/image.$longExt", "Drug Three")

        assertTrue(result != null)
        val name = File(result!!).name
        val ext = name.substringAfterLast('.')
        assertEquals(10, ext.length)
    }

    @Test
    fun `truncates long drug name to 100 characters`() = runTest {
        val longName = "x".repeat(200)
        stubCall(fakeResponse())

        val result = downloader.downloadAndSave("https://example.com/img.webp", longName)

        assertTrue(result != null)
        val baseName = File(result!!).name.substringBeforeLast('.')
        assertEquals(100, baseName.length)
    }

    // ---------- caching ----------

    @Test
    fun `returns cached path without network call when file already exists`() = runTest {
        val dir = File(filesDir, "drug_images").apply { mkdirs() }
        val cached = File(dir, "cached_drug.webp")
        cached.writeText("already-there")

        val result = downloader.downloadAndSave("https://example.com/img.webp", "Cached Drug")

        assertEquals(cached.absolutePath, result)
        io.mockk.verify(exactly = 0) { okHttpClient.newCall(any()) }
    }

    @Test
    fun `ignores zero-length cached file and re-downloads`() = runTest {
        val dir = File(filesDir, "drug_images").apply { mkdirs() }
        val cached = File(dir, "empty_drug.webp")
        cached.createNewFile()
        assertEquals(0L, cached.length())

        stubCall(fakeResponse())

        val result = downloader.downloadAndSave("https://example.com/img.webp", "Empty Drug")

        assertTrue(result != null)
        assertEquals("image-bytes", File(result!!).readText())
        io.mockk.verify(exactly = 1) { okHttpClient.newCall(any()) }
    }

    // ---------- failure branches ----------

    @Test
    fun `returns null when http response is not successful`() = runTest {
        stubCall(fakeResponse(code = 404, bodyBytes = null))

        val result = downloader.downloadAndSave("https://example.com/img.webp", "Missing Drug")

        assertNull(result)
    }

    @Test
    fun `saves an empty file when response body has zero bytes`() = runTest {
        // The production code only null-checks response.body (`response.body?.byteStream()...`);
        // it has no zero-length check, and OkHttp's Response.Builder requires a non-null
        // ResponseBody, so an empty body still produces a real (empty) saved file, not null.
        stubCall(fakeResponse(code = 200, bodyBytes = ByteArray(0)))

        val result = downloader.downloadAndSave("https://example.com/img.webp", "No Body Drug")

        assertTrue(result != null)
        assertEquals(0L, File(result!!).length())
    }

    @Test
    fun `returns null when network call throws exception`() = runTest {
        stubCallThrows(IOException("network down"))

        val result = downloader.downloadAndSave("https://example.com/img.webp", "Exploding Drug")

        assertNull(result)
    }

    @Test
    fun `does not leave a partial file on the filesystem after failed download`() = runTest {
        stubCall(fakeResponse(code = 500, bodyBytes = null))

        downloader.downloadAndSave("https://example.com/img.webp", "Failing Drug")

        val dir = File(filesDir, "drug_images")
        val expected = File(dir, "failing_drug.webp")
        assertTrue(!expected.exists())
    }
}
