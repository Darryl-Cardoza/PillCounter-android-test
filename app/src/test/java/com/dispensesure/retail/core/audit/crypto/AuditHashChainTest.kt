package com.dispensesure.retail.core.audit.crypto

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class AuditHashChainTest {

    // Independent of Sha256, so a bug there can't hide here.
    private fun sha(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Test
    fun `starts at the hash of an empty string`() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", AuditHashChain.START_HASH)
        assertEquals(AuditHashChain.START_HASH, AuditHashChain.lastHash(emptyList()))
    }

    @Test
    fun `each line is hashed with the previous hash in front`() {
        val h1 = sha(AuditHashChain.START_HASH + "{\"seq\":1}")
        val h2 = sha(h1 + "{\"seq\":2}")
        assertEquals(h1, AuditHashChain.lastHash(listOf("{\"seq\":1}")))
        assertEquals(h2, AuditHashChain.lastHash(listOf("{\"seq\":1}", "{\"seq\":2}")))
    }

    @Test
    fun `changing or reordering a line changes the tail`() {
        val original = AuditHashChain.lastHash(listOf("a", "b"))
        assertEquals(false, original == AuditHashChain.lastHash(listOf("b", "a")))
        assertEquals(false, original == AuditHashChain.lastHash(listOf("a", "c")))
    }
}
