package com.dispensesure.retail.core.audit.upload

import org.junit.Assert.assertEquals
import org.junit.Test

class AuditApiReplyTest {

    private fun envelope(data: String) = """{"status":0,"is_success":false,"message":"m","token":null,"data":$data}"""

    @Test
    fun `reads code and next free sequence`() {
        assertEquals(
            AuditApiReply(409, "slot_occupied", nextFreeSequence = 4),
            AuditApiReply.parse(409, envelope("""{"code":"slot_occupied","next_free_sequence":4}"""))
        )
    }

    @Test
    fun `a 201 body without a code still parses`() {
        assertEquals(AuditApiReply(201), AuditApiReply.parse(201, envelope("""{"receipt_id":"r-1","is_saved":true}""")))
    }

    @Test
    fun `a body that is not the envelope gives just the status`() {
        assertEquals(AuditApiReply(413), AuditApiReply.parse(413, "<html>Request Entity Too Large</html>"))
    }

    @Test
    fun `no body or null data gives just the status`() {
        assertEquals(AuditApiReply(503), AuditApiReply.parse(503, null))
        assertEquals(AuditApiReply(401), AuditApiReply.parse(401, envelope("null")))
    }

    @Test
    fun `from reads the error body of a failed response`() {
        assertEquals(AuditApiReply(422, "checksum_mismatch"), AuditApiReply.from(reply(422, "checksum_mismatch")))
    }
}
