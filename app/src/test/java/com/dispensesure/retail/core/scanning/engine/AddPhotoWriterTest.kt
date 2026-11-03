package com.dispensesure.retail.core.scanning.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import com.dispensesure.retail.core.region.Region
import com.dispensesure.retail.core.room.dao.UserDao
import com.dispensesure.retail.core.room.models.UserEntity
import com.dispensesure.retail.core.scanning.logic.TrayClass
import com.dispensesure.retail.core.scanning.logic.TrayDetection
import com.dispensesure.retail.core.security.ImageCrypto
import com.dispensesure.retail.core.utils.common.HelperFunctions
import com.dispensesure.retail.core.utils.common.LocationProvider
import com.dispensesure.retail.core.utils.common.OverlayUtils
import com.dispensesure.retail.core.utils.common.PhotoInfo
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * Unit tests for [AddPhotoWriter]: crop to tray + chute, processed and raw JPEG files, grayscale,
 * strip text. NATIVE graphics so bitmaps and JPEGs are real; strip drawing has its own tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddPhotoWriterTest {

    @get:Rule
    val picturesFolder = TemporaryFolder()

    private val context: Context = mockk(relaxed = true)
    private val locationProvider: LocationProvider = mockk(relaxed = true)
    private val userDao: UserDao = mockk(relaxed = true)
    private val preferenceHelper: PreferenceHelper = mockk(relaxed = true)

    private lateinit var writerScope: CoroutineScope
    private lateinit var addPhotoWriter: AddPhotoWriter
    private lateinit var stampedPhotoInfo: CapturingSlot<PhotoInfo>

    private val label = PhotoLabel(
        drugName = "Amoxicillin 500 mg",
        drugImagePath = null,
        isStockCount = false,
        requestedNdc = "11111-1111-11",
        dispensedNdc = "22222-2222-22",
        lotNumber = "L1",
        serialNumber = "S1",
        expirationDate = "04-30-2028",
        rxRefill = "123-2",
        stepLabel = "TARGET_VERIFICATION",
        region = Region.US,
    )

    // A tray with its chute below it, inside a 400 x 300 frame.
    private val trayAndChute = listOf(
        TrayDetection(RectF(100f, 50f, 300f, 200f), 0.9f, cls = TrayClass.TRAY),
        TrayDetection(RectF(150f, 200f, 250f, 250f), 0.9f, cls = TrayClass.CHUTE),
    )

    @Before
    fun setup() {
        every { context.getExternalFilesDir(any()) } returns picturesFolder.root
        every { context.getString(any<Int>()) } returns "Location unavailable"
        // No placeholder drawable, so the strip has no drug image.
        every { context.getDrawable(any()) } returns null
        every { preferenceHelper.getUserId() } returns "user-1"
        coEvery { userDao.getByUserId("user-1") } returns null
        // No AndroidKeyStore here; files are written plain so the test can read them back.
        mockkObject(ImageCrypto)
        every { ImageCrypto.encrypt(any()) } answers { firstArg() }
        // Only records what the strip would show; crop helpers stay real.
        mockkObject(OverlayUtils)
        stampedPhotoInfo = slot()
        every {
            OverlayUtils.drawDetectionsOnBitmap(any(), any(), capture(stampedPhotoInfo), any(), any(), any())
        } answers { firstArg() }
        writerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        addPhotoWriter = AddPhotoWriter(writerScope, context, locationProvider, userDao, preferenceHelper)
    }

    @After
    fun tearDown() {
        writerScope.cancel()
        unmockkAll()
    }

    private fun solidFrame(color: Int = Color.RED): Bitmap =
        Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun capturedAdd(frame: Bitmap, trays: List<TrayDetection> = emptyList(), count: Int = 5) =
        CaptureResult.Captured(count = count, frame = frame, filteredPills = emptyList(), trays = trays)

    private fun savedPhotoFiles(): List<File> =
        File(picturesFolder.root, "transaction_details").listFiles().orEmpty().toList()

    @Test
    fun `a frame with a tray and chute is saved cropped to them`() = runTest {
        val expectedCrop = OverlayUtils.savedPhotoCropRegion(trayAndChute, 400, 300)!!

        val savedPhoto = addPhotoWriter.write(capturedAdd(solidFrame(), trayAndChute), label, keepRawCrop = false, grayscale = false)

        val photoOnDisk = BitmapFactory.decodeFile(savedPhoto.imagePath)
        assertEquals(expectedCrop.width(), photoOnDisk.width)
        assertEquals(expectedCrop.height(), photoOnDisk.height)
        assertTrue(File(savedPhoto.imagePath!!).name.startsWith("txn_detail_"))
    }

    @Test
    fun `a frame with no tray is saved whole`() = runTest {
        val savedPhoto = addPhotoWriter.write(capturedAdd(solidFrame()), label, keepRawCrop = false, grayscale = false)

        val photoOnDisk = BitmapFactory.decodeFile(savedPhoto.imagePath)
        assertEquals(400, photoOnDisk.width)
        assertEquals(300, photoOnDisk.height)
    }

    @Test
    fun `without keepRawCrop only one file is written`() = runTest {
        val savedPhoto = addPhotoWriter.write(capturedAdd(solidFrame(), trayAndChute), label, keepRawCrop = false, grayscale = false)

        assertNull(savedPhoto.rawImagePath)
        assertEquals(1, savedPhotoFiles().size)
    }

    @Test
    fun `keepRawCrop also writes the same crop with nothing drawn on it`() = runTest {
        val savedPhoto = addPhotoWriter.write(capturedAdd(solidFrame(), trayAndChute), label, keepRawCrop = true, grayscale = false)

        assertTrue(savedPhoto.rawImagePath!!.endsWith("_raw.jpg"))
        val photoOnDisk = BitmapFactory.decodeFile(savedPhoto.imagePath)
        val rawPhotoOnDisk = BitmapFactory.decodeFile(savedPhoto.rawImagePath)
        assertEquals(photoOnDisk.width, rawPhotoOnDisk.width)
        assertEquals(photoOnDisk.height, rawPhotoOnDisk.height)
        // The strip is drawn once, for the processed photo only.
        verify(exactly = 1) { OverlayUtils.drawDetectionsOnBitmap(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `grayscale makes the photo gray and keeps the raw crop in colour`() = runTest {
        val savedPhoto = addPhotoWriter.write(
            capturedAdd(solidFrame(Color.RED), trayAndChute), label, keepRawCrop = true, grayscale = true,
        )

        val photoPixel = BitmapFactory.decodeFile(savedPhoto.imagePath).getPixel(10, 10)
        assertTrue(abs(Color.red(photoPixel) - Color.green(photoPixel)) < 10)
        val rawPhotoPixel = BitmapFactory.decodeFile(savedPhoto.rawImagePath).getPixel(10, 10)
        assertTrue(Color.red(rawPhotoPixel) > 200 && Color.green(rawPhotoPixel) < 60)
    }

    @Test
    fun `the counted frame is recycled after the write`() = runTest {
        val frame = solidFrame()

        addPhotoWriter.write(capturedAdd(frame, trayAndChute), label, keepRawCrop = true, grayscale = false)

        assertTrue(frame.isRecycled)
    }

    @Test
    fun `the strip gets the label, the count, the operator and the location fallback`() = runTest {
        coEvery { userDao.getByUserId("user-1") } returns UserEntity(userId = "user-1", fName = "Jane", lName = "Doe")

        addPhotoWriter.write(capturedAdd(solidFrame(), count = 7), label, keepRawCrop = false, grayscale = false)

        with(stampedPhotoInfo.captured) {
            assertEquals("Amoxicillin 500 mg", drugName)
            assertEquals("11111-1111-11", requestedNdc)
            assertEquals("22222-2222-22", dispensedNdc)
            assertEquals("L1", lotNumber)
            assertEquals("S1", serialNumber)
            assertEquals("04-30-2028", expirationDate)
            assertEquals("123-2", rxRefill)
            assertEquals("TARGET_VERIFICATION", stepLabel)
            assertEquals(7, count)
            assertEquals("Jane Doe", userName)
            // No lookup has finished yet, so the fallback text is shown.
            assertEquals("Location unavailable", location)
        }
    }

    @Test
    fun `the photo with the strip is recycled after the write`() = runTest {
        val photoWithStrip = solidFrame()
        every { OverlayUtils.drawDetectionsOnBitmap(any(), any(), any(), any(), any(), any()) } returns photoWithStrip

        addPhotoWriter.write(capturedAdd(solidFrame(), trayAndChute), label, keepRawCrop = false, grayscale = false)

        assertTrue(photoWithStrip.isRecycled)
    }

    @Test
    fun `the frame is recycled once when drawing fell back to it`() = runTest {
        // No tray, so the crop is the frame; drawing returns its input, so all three are one bitmap.
        mockkObject(HelperFunctions)
        every { HelperFunctions.saveBitmapToFile(any(), any(), any(), any(), any()) } returns "/photos/txn_detail.jpg"
        val frame = mockk<Bitmap>(relaxed = true)

        addPhotoWriter.write(capturedAdd(frame), label, keepRawCrop = false, grayscale = false)

        verify(exactly = 1) { frame.recycle() }
    }

    @Test
    fun `a failed write still refreshes the location`() = runTest {
        coEvery { userDao.getByUserId("user-1") } throws IllegalStateException("db closed")

        runCatching { addPhotoWriter.write(capturedAdd(solidFrame()), label, keepRawCrop = false, grayscale = false) }

        coVerify(timeout = 3000) { locationProvider.getCurrentLocationAsString() }
    }

    @Test
    fun `refreshLocation starts a location lookup`() {
        addPhotoWriter.refreshLocation()

        coVerify(timeout = 3000) { locationProvider.getCurrentLocationAsString() }
    }
}
