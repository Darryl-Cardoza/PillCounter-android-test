package com.rite.pillcounting.feature.hl7.util

/**
 * Returns true when the raw HL7 ACK carries a success acknowledgment code in MSA-1.
 * Accepts "AA" (Application Accept) and "CA" (Commit Accept, enhanced mode); "AE"/"AR"
 * (error/reject) and a missing MSA segment are treated as non-success.
 */
fun isSuccessAck(ackRaw: String): Boolean {
    val msaSegment = ackRaw
        .split('\r', '\n')
        .firstOrNull { it.startsWith("MSA|") }
        ?: return false
    val code = msaSegment.split('|').getOrNull(1)?.trim()?.uppercase()
    return code == "AA" || code == "CA"
}
