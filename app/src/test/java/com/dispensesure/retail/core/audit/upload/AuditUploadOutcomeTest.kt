package com.dispensesure.retail.core.audit.upload

import com.dispensesure.retail.core.audit.upload.AuditUploadOutcome.DONE
import com.dispensesure.retail.core.audit.upload.AuditUploadOutcome.PARK
import com.dispensesure.retail.core.audit.upload.AuditUploadOutcome.REFILE
import com.dispensesure.retail.core.audit.upload.AuditUploadOutcome.REJECT
import com.dispensesure.retail.core.audit.upload.AuditUploadOutcome.RETRY_LATER
import org.junit.Assert.assertEquals
import org.junit.Test

class AuditUploadOutcomeTest {

    private fun outcome(status: Int, code: String? = null, nextFreeSequence: Int? = null) =
        AuditUploadOutcome.of(AuditApiReply(status, code, nextFreeSequence))

    @Test
    fun `stored and duplicates are done`() {
        assertEquals(DONE, outcome(201))
        assertEquals(DONE, outcome(201, "stored"))
        assertEquals(DONE, outcome(200, "duplicate_replay"))
        assertEquals(DONE, outcome(200, "duplicate_repaired"))
    }

    @Test
    fun `not indexed, server errors and the offline gate retry later`() {
        assertEquals(RETRY_LATER, outcome(202, "stored_not_indexed"))
        assertEquals(RETRY_LATER, outcome(503, "storage_backend_unavailable"))
        assertEquals(RETRY_LATER, outcome(500))
        assertEquals(RETRY_LATER, outcome(599))
        assertEquals(RETRY_LATER, outcome(200, "something_new"))
    }

    @Test
    fun `slot occupied refiles only with a next free sequence`() {
        assertEquals(REFILE, outcome(409, "slot_occupied", nextFreeSequence = 2))
        assertEquals(REJECT, outcome(409, "slot_occupied"))
    }

    @Test
    fun `sign-in and onboarding problems park`() {
        assertEquals(PARK, outcome(401))
        assertEquals(PARK, outcome(403, "device_not_active"))
        assertEquals(PARK, outcome(403))
        assertEquals(PARK, outcome(409, "pharmacy_id_not_claimed"))
    }

    @Test
    fun `other client errors are rejected`() {
        assertEquals(REJECT, outcome(400, "empty_upload"))
        assertEquals(REJECT, outcome(413))
        assertEquals(REJECT, outcome(422, "invalid_field"))
        assertEquals(REJECT, outcome(422, "checksum_mismatch"))
    }
}
