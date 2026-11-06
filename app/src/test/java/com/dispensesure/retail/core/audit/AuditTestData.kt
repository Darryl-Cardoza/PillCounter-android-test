package com.dispensesure.retail.core.audit

import com.dispensesure.retail.core.audit.model.AuditRecord
import com.dispensesure.retail.core.audit.model.AuditResult
import com.dispensesure.retail.core.audit.model.AuditSegmentMeta
import kotlinx.serialization.json.buildJsonObject

object AuditTestData {
    val sampleMeta = AuditSegmentMeta(
        sha256 = "a".repeat(64), chainLastHash = "c".repeat(64), dekKeyId = "1", dekVersion = 1,
        eventCount = 2, firstEventAt = "2026-10-21T10:00:00Z", lastEventAt = "2026-10-21T11:00:00Z"
    )

    fun record(seq: Long, timestamp: String = "2026-10-21T10:00:00Z") = AuditRecord(
        eventId = "e-$seq", seq = seq, timestamp = timestamp, sessionId = "s-1",
        userId = null, email = null, operatorProfileId = null, operatorName = null, deviceKey = null, terminalId = null,
        appVersion = "2.4.1 (241)", tag = "X", result = AuditResult.SUCCESS, data = buildJsonObject { }
    )
}
