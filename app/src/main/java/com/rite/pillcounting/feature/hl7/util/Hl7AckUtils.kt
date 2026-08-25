package com.rite.pillcounting.feature.hl7.util

/** Extracts and uppercases MSA-1 (the ack code) from a raw HL7 ACK, or null if there's no MSA segment. */
private fun msaCode(ackRaw: String): String? {
    val msaSegment = ackRaw
        .split('\r', '\n')
        .firstOrNull { it.startsWith("MSA|") }
        ?: return null
    return msaSegment.split('|').getOrNull(1)?.trim()?.uppercase()
}

/**
 * Returns true when the raw HL7 ACK carries a success acknowledgment code in MSA-1.
 * Accepts "AA" (Application Accept) and "CA" (Commit Accept, enhanced mode); "AE"/"AR"
 * (error/reject) and a missing MSA segment are treated as non-success.
 */
fun isSuccessAck(ackRaw: String): Boolean {
    val code = msaCode(ackRaw)
    return code == "AA" || code == "CA"
}

/**
 * Returns true when the raw HL7 ACK carries an explicit reject code (MSA-1 == "AR")
 * in MSA-1 — PMS rejected the message itself, as opposed to a missing ACK/timeout,
 * which is a transient transport failure and should still be retried.
 */
fun isRejectAck(ackRaw: String): Boolean {
    return msaCode(ackRaw) == "AR"
}
