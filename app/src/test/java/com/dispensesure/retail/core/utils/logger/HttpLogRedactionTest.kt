package com.dispensesure.retail.core.utils.logger

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpLogRedactionTest {

    @Test
    fun `every confidential field is hidden`() {
        listOf("access_token", "refresh_token", "token", "key_material", "dek", "otp", "pin").forEach { field ->
            assertEquals("""{"$field":"***"}""", HttpLogRedaction.redact("""{"$field":"s3cret"}"""))
        }
    }

    @Test
    fun `number values and any spacing are hidden too`() {
        assertEquals("""{"pin":"***","otp":"***"}""", HttpLogRedaction.redact("""{"pin": 1234,"otp" :  "998877"}"""))
    }

    @Test
    fun `an escaped quote inside a value does not leak the rest`() {
        assertEquals("""{"token":"***","a":1}""", HttpLogRedaction.redact("""{"token":"ab\"cd","a":1}"""))
    }

    @Test
    fun `similar names and plain text are kept`() {
        val text = """{"device_key":"d-1","dek_key_id":"dek-d-1","tokens_left":3} --> POST /mobile/devices/keys"""
        assertEquals(text, HttpLogRedaction.redact(text))
    }
}
