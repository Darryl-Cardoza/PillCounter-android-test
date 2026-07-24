package com.rite.pillcounting.core.utils.coil

import coil.decode.DataSource
import coil.fetch.SourceResult
import coil.request.Options
import com.rite.pillcounting.core.security.ImageCrypto
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

class EncryptedImageFetcherTest {

    private lateinit var options: Options
    private lateinit var tempFile: File

    @Before
    fun setUp() {
        options = mockk(relaxed = true)
        mockkObject(ImageCrypto)
        tempFile = File.createTempFile("encrypted_image_fetcher_test", ".jpg")
    }

    @After
    fun tearDown() {
        unmockkObject(ImageCrypto)
        tempFile.delete()
    }

    @Test
    fun `fetch decrypts bytes when file is encrypted`() = runTest {
        val raw = byteArrayOf(1, 2, 3, 4)
        val decrypted = byteArrayOf(9, 9, 9)
        tempFile.writeBytes(raw)
        every { ImageCrypto.isEncrypted(raw) } returns true
        every { ImageCrypto.decrypt(raw) } returns decrypted

        val fetcher = EncryptedImageFetcher(tempFile, options)
        val result = fetcher.fetch()

        assertTrueSourceResult(result)
        val buffer = (result as SourceResult).source.source().buffer
        assertEquals("image/jpeg", result.mimeType)
        assertEquals(DataSource.DISK, result.dataSource)
        assertEquals(decrypted.size.toLong(), buffer.size)
        assertEquals(decrypted.toList(), buffer.readByteArray().toList())
    }

    @Test
    fun `fetch falls back to raw bytes when decrypt throws`() = runTest {
        val raw = byteArrayOf(5, 6, 7)
        tempFile.writeBytes(raw)
        every { ImageCrypto.isEncrypted(raw) } returns true
        every { ImageCrypto.decrypt(raw) } throws RuntimeException("corrupted")

        val fetcher = EncryptedImageFetcher(tempFile, options)
        val result = fetcher.fetch() as SourceResult

        val buffer = result.source.source().buffer
        assertEquals(raw.toList(), buffer.readByteArray().toList())
    }

    @Test
    fun `fetch passes through plain unencrypted bytes unchanged`() = runTest {
        val raw = byteArrayOf(10, 11, 12)
        tempFile.writeBytes(raw)
        every { ImageCrypto.isEncrypted(raw) } returns false

        val fetcher = EncryptedImageFetcher(tempFile, options)
        val result = fetcher.fetch() as SourceResult

        val buffer = result.source.source().buffer
        assertEquals(raw.toList(), buffer.readByteArray().toList())
        assertEquals("image/jpeg", result.mimeType)
        assertEquals(DataSource.DISK, result.dataSource)
    }

    @Test
    fun `fetch handles empty file as unencrypted`() = runTest {
        tempFile.writeBytes(ByteArray(0))
        every { ImageCrypto.isEncrypted(ByteArray(0)) } returns false

        val fetcher = EncryptedImageFetcher(tempFile, options)
        val result = fetcher.fetch() as SourceResult

        val buffer = result.source.source().buffer
        assertEquals(0L, buffer.size)
    }

    @Test
    fun `factory create returns fetcher when file exists`() {
        val imageLoader = mockk<coil.ImageLoader>(relaxed = true)
        val factory = EncryptedImageFetcher.Factory()

        val fetcher = factory.create(tempFile, options, imageLoader)

        assertNotNull(fetcher)
        assertEquals(EncryptedImageFetcher::class.java, fetcher!!::class.java)
    }

    @Test
    fun `factory create returns null when file does not exist`() {
        val imageLoader = mockk<coil.ImageLoader>(relaxed = true)
        val missingFile = File(tempFile.parentFile, "does_not_exist_${System.nanoTime()}.jpg")
        val factory = EncryptedImageFetcher.Factory()

        val fetcher = factory.create(missingFile, options, imageLoader)

        assertNull(fetcher)
    }

    private fun assertTrueSourceResult(result: coil.fetch.FetchResult) {
        org.junit.Assert.assertTrue(result is SourceResult)
    }
}
