package com.dispensesure.retail.core.utils.logger.destination

import com.dispensesure.retail.core.utils.logger.destination.dto.RemoteLogRequest
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Queued on-device entries written before `build_number` existed must still parse, unstamped. */
class RemoteLogRequestTest {

    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(RemoteLogRequest::class.java)

    private fun json(extra: String = "") = """
        {"device_key":null,"app_name":"app","app_version":"1.0.0 (1)",$extra
         "platform":"android","os_version":"14","device_model":"m","session_id":"s","log_id":"l",
         "severity":3,"timestamp":"t","message":"msg","tag":"tag","event":"e",
         "network":{"type":"wifi","is_online":true}}
    """.trimIndent()

    @Test
    fun `legacy queued entry without build_number parses with null`() {
        val request = adapter.fromJson(json())
        assertNotNull(request)
        assertNull(request!!.buildNumber)
    }

    @Test
    fun `build_number round-trips`() {
        val request = adapter.fromJson(json("\"build_number\":\"42\","))!!
        assertEquals("42", request.buildNumber)
        assertEquals("42", adapter.fromJson(adapter.toJson(request))!!.buildNumber)
    }
}
