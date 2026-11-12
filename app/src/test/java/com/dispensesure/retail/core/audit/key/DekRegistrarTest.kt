package com.dispensesure.retail.core.audit.key

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dispensesure.retail.BuildConfig
import com.dispensesure.retail.core.audit.crypto.Sha256
import com.dispensesure.retail.core.audit.upload.reply
import com.dispensesure.retail.core.room.AppDatabase
import com.dispensesure.retail.core.room.dao.DekVersionDao
import com.dispensesure.retail.core.room.models.DekVersionEntity
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Response
import java.io.IOException
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class DekRegistrarTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: DekVersionDao
    private val api = mockk<IDeviceKeysApi>()
    private val preferenceHelper = mockk<PreferenceHelper>(relaxed = true).also {
        every { it.getAccessToken() } returns "token"
    }
    private lateinit var registrar: DekRegistrar
    private val sentBodies = mutableListOf<JsonObject>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.dekVersionDao()
        registrar = DekRegistrar(dao, api, preferenceHelper)
    }

    @After
    fun tearDown() { db.close() }

    private suspend fun addRow(kekKeyId: String, wrappedDek: ByteArray, version: Int = 1, isRegistered: Boolean = false) =
        dao.insertAsActive(
            DekVersionEntity(
                dekKeyId = "dek-dev-1", version = version, wrappedDek = wrappedDek,
                kekKeyId = kekKeyId, kekVersion = 1, kekAlgorithm = "AES-256-GCM",
                isActive = true, isRegistered = isRegistered, createdAt = 1000L
            )
        )

    private fun answerRegistrations(replyFor: (sentBody: JsonObject) -> Response<ResponseBody>) {
        coEvery { api.registerKey(any(), any()) } answers {
            val sentBody = Json.parseToJsonElement(Buffer().also { secondArg<RequestBody>().writeTo(it) }.readUtf8()).jsonObject
            sentBodies += sentBody
            replyFor(sentBody)
        }
    }

    private fun stored(wrappedDek: ByteArray) = reply(201, "stored", ""","fingerprint":"${Sha256.hexOf(wrappedDek)}"""")
    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content

    @Test
    fun `no rows sends nothing`() = runTest {
        assertNull(registrar.registerIfNeeded("dev-1"))
        coVerify(exactly = 0) { api.registerKey(any(), any()) }
    }

    @Test
    fun `a registered active row is returned without a request`() = runTest {
        addRow("kek-1", byteArrayOf(1), isRegistered = true)
        assertEquals("kek-1", registrar.registerIfNeeded("dev-1")!!.kekKeyId)
        coVerify(exactly = 0) { api.registerKey(any(), any()) }
    }

    @Test
    fun `a dek wrapped only by the bootstrap key is not sent`() = runTest {
        addRow("local-bootstrap", byteArrayOf(1))
        assertNull(registrar.registerIfNeeded("dev-1"))
        coVerify(exactly = 0) { api.registerKey(any(), any()) }
    }

    @Test
    fun `201 with a matching fingerprint registers the version`() = runTest {
        val wrappedDek = ByteArray(66) { 3 }
        addRow("local-bootstrap", byteArrayOf(1))
        addRow("kek-1", wrappedDek)
        answerRegistrations { stored(wrappedDek) }

        val registered = registrar.registerIfNeeded("dev-1")!!

        assertEquals(true, registered.isRegistered)
        val body = sentBodies.single()
        assertEquals("dev-1", body.text("device_key"))
        assertEquals("dek-dev-1", body.text("dek_key_id"))
        assertEquals("1", body.text("version"))
        assertEquals(Base64.getEncoder().encodeToString(wrappedDek), body.text("dek"))
        assertEquals("kek-1", body.text("kek_key_id"))
        assertEquals("1", body.text("kek_version"))
        assertEquals("AES-256-GCM", body.text("algorithm"))
        assertEquals("256", body.text("key_length_bits"))
        assertEquals("android", body.text("platform"))
        assertEquals(BuildConfig.VERSION_NAME, body.text("app_version"))
        assertEquals(listOf(false, true), dao.getAll().map { it.isRegistered })
    }

    @Test
    fun `200 duplicate_replay also registers`() = runTest {
        val wrappedDek = ByteArray(66) { 3 }
        addRow("kek-1", wrappedDek)
        answerRegistrations { reply(200, "duplicate_replay", ""","fingerprint":"${Sha256.hexOf(wrappedDek)}"""") }

        assertEquals(true, registrar.registerIfNeeded("dev-1")!!.isRegistered)
    }

    @Test
    fun `a fingerprint mismatch leaves it unregistered`() = runTest {
        addRow("kek-1", ByteArray(66) { 3 })
        answerRegistrations { reply(201, "stored", ""","fingerprint":"0000"""") }

        assertNull(registrar.registerIfNeeded("dev-1"))
        assertEquals(false, dao.getActive()!!.isRegistered)
    }

    @Test
    fun `version_conflict renumbers and resends the same bytes`() = runTest {
        val wrappedDek = ByteArray(66) { 3 }
        addRow("local-bootstrap", byteArrayOf(1))
        addRow("kek-1", wrappedDek)
        answerRegistrations { sentBody ->
            if (sentBody.text("version") == "1") reply(409, "version_conflict", ""","next_free_version":2""")
            else stored(wrappedDek)
        }

        val registered = registrar.registerIfNeeded("dev-1")!!

        assertEquals(listOf("1", "2"), sentBodies.map { it.text("version") })
        assertEquals(1, sentBodies.map { it.text("dek") }.toSet().size)
        assertEquals(2, registered.version)
        assertEquals(listOf(2, 2), dao.getAll().map { it.version })
    }

    @Test
    fun `while pending, a kek rotation still sends the first server-wrapped row`() = runTest {
        val firstWrap = ByteArray(66) { 3 }
        addRow("kek-1", firstWrap)
        addRow("kek-2", ByteArray(66) { 4 })
        answerRegistrations { stored(firstWrap) }

        val registered = registrar.registerIfNeeded("dev-1")!!

        assertEquals("kek-1", sentBodies.single().text("kek_key_id"))
        assertEquals("kek-2", registered.kekKeyId)
        assertEquals(listOf(true, true), dao.getAll().map { it.isRegistered })
    }

    @Test
    fun `server busy leaves it unregistered`() = runTest {
        addRow("kek-1", ByteArray(66) { 3 })
        answerRegistrations { reply(503, "encryption_unavailable") }
        assertNull(registrar.registerIfNeeded("dev-1"))
        assertEquals(false, dao.getActive()!!.isRegistered)
    }

    @Test
    fun `a network error leaves it unregistered`() = runTest {
        addRow("kek-1", ByteArray(66) { 3 })
        coEvery { api.registerKey(any(), any()) } throws IOException("offline")
        assertNull(registrar.registerIfNeeded("dev-1"))
    }

    @Test
    fun `no access token sends nothing`() = runTest {
        every { preferenceHelper.getAccessToken() } returns null
        addRow("kek-1", ByteArray(66) { 3 })
        assertNull(registrar.registerIfNeeded("dev-1"))
        coVerify(exactly = 0) { api.registerKey(any(), any()) }
    }
}
