package com.rite.pillcounting.core.security

import android.content.Context
import android.util.Base64
import com.rite.pillcounting.core.utils.preference.SecurePreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.util.Base64 as JavaBase64

class DatabaseKeyProviderTest {

    private val context: Context = mockk(relaxed = true)

    @Before
    fun setUp() {
        mockkConstructor(SecurePreferences::class)
        mockkStatic(Base64::class)

        // Delegate android.util.Base64 to java.util.Base64 so encode/decode round-trip correctly.
        every { Base64.encodeToString(any(), any()) } answers {
            JavaBase64.getEncoder().encodeToString(firstArg())
        }
        every { Base64.decode(any<String>(), any()) } answers {
            JavaBase64.getDecoder().decode(firstArg<String>())
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `returns decoded key when an encrypted key already exists in prefs`() {
        val rawKey = ByteArray(32) { it.toByte() }
        val encoded = JavaBase64.getEncoder().encodeToString(rawKey)
        every { anyConstructed<SecurePreferences>().getString("encrypted_db_key") } returns encoded

        val result = DatabaseKeyProvider.getOrCreateDatabaseKey(context)

        assertArrayEquals(rawKey, result)
    }

    @Test
    fun `generates and persists a new 32-byte key when none exists`() {
        every { anyConstructed<SecurePreferences>().getString("encrypted_db_key") } returns null
        val putSlot = slot<String>()
        every { anyConstructed<SecurePreferences>().putString("encrypted_db_key", capture(putSlot)) } returns Unit

        val result = DatabaseKeyProvider.getOrCreateDatabaseKey(context)

        assertEquals(32, result.size)
        verify(exactly = 1) {
            anyConstructed<SecurePreferences>().putString("encrypted_db_key", any())
        }
        // The persisted value must decode back to exactly the returned key.
        val persistedDecoded = JavaBase64.getDecoder().decode(putSlot.captured)
        assertArrayEquals(result, persistedDecoded)
    }

    @Test
    fun `generated key is random across invocations`() {
        every { anyConstructed<SecurePreferences>().getString("encrypted_db_key") } returns null
        every { anyConstructed<SecurePreferences>().putString("encrypted_db_key", any()) } returns Unit

        val first = DatabaseKeyProvider.getOrCreateDatabaseKey(context)
        val second = DatabaseKeyProvider.getOrCreateDatabaseKey(context)

        assertNotNull(first)
        assertNotNull(second)
        assert(!first.contentEquals(second)) { "Expected two independently generated keys to differ" }
    }

    @Test
    fun `empty string existing value is treated as present and decoded`() {
        // Base64 decode of "" yields an empty byte array; this exercises the non-null branch
        // with a boundary (empty) input rather than the key-generation branch.
        every { anyConstructed<SecurePreferences>().getString("encrypted_db_key") } returns ""

        val result = DatabaseKeyProvider.getOrCreateDatabaseKey(context)

        assertEquals(0, result.size)
        verify(exactly = 0) {
            anyConstructed<SecurePreferences>().putString(any(), any())
        }
    }
}
