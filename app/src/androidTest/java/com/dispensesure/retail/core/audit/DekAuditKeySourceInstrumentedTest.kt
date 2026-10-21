package com.dispensesure.retail.core.audit

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dispensesure.retail.core.audit.crypto.AuditRecordCipher
import com.dispensesure.retail.core.audit.crypto.DekAuditKeySource
import com.dispensesure.retail.core.security.DatabaseKeyProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DekAuditKeySourceInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun separateInstancesDeriveTheSameKeyFromTheDek() {
        assertArrayEquals(DekAuditKeySource(context).key().encoded, DekAuditKeySource(context).key().encoded)
    }

    @Test
    fun derivedKeyIsNotTheRawDek() {
        val dek = DatabaseKeyProvider.getOrCreateDatabasePassphrase(context)
        assertFalse(dek.contentEquals(DekAuditKeySource(context).key().encoded))
    }

    @Test
    fun recordEncryptedWithTheDekKeyDecryptsBack() {
        val cipher = AuditRecordCipher(DekAuditKeySource(context))
        assertEquals("""{"tag":"X"}""", cipher.decrypt(cipher.encrypt("""{"tag":"X"}""")))
    }
}
