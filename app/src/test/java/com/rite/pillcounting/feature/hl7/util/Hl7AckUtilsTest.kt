package com.rite.pillcounting.feature.hl7.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [isSuccessAck] and [isRejectAck].
 */
class Hl7AckUtilsTest {

    private fun ackWith(msaCode: String, controlId: String = "CTRL1"): String =
        "MSH|^~\\&|PMS|PMSFAC|APP|APPFAC|20240101120000||ACK|$controlId|P|2.5\r" +
            "MSA|$msaCode|$controlId"

    // ──────────────────────────── isSuccessAck ────────────────────────────

    @Test
    fun `isSuccessAck true for AA`() {
        assertTrue(isSuccessAck(ackWith("AA")))
    }

    @Test
    fun `isSuccessAck true for CA`() {
        assertTrue(isSuccessAck(ackWith("CA")))
    }

    @Test
    fun `isSuccessAck is case-insensitive`() {
        assertTrue(isSuccessAck(ackWith("aa")))
    }

    @Test
    fun `isSuccessAck false for AE`() {
        assertFalse(isSuccessAck(ackWith("AE")))
    }

    @Test
    fun `isSuccessAck false for AR`() {
        assertFalse(isSuccessAck(ackWith("AR")))
    }

    @Test
    fun `isSuccessAck false when no MSA segment present`() {
        assertFalse(isSuccessAck("MSH|^~\\&|PMS|PMSFAC|APP|APPFAC|20240101120000||ACK|CTRL1|P|2.5"))
    }

    @Test
    fun `isSuccessAck false for blank input`() {
        assertFalse(isSuccessAck(""))
    }

    // ──────────────────────────── isRejectAck ────────────────────────────

    @Test
    fun `isRejectAck true for AR`() {
        assertTrue(isRejectAck(ackWith("AR")))
    }

    @Test
    fun `isRejectAck is case-insensitive`() {
        assertTrue(isRejectAck(ackWith("ar")))
    }

    @Test
    fun `isRejectAck false for AA`() {
        assertFalse(isRejectAck(ackWith("AA")))
    }

    @Test
    fun `isRejectAck false for AE`() {
        // AE (application error) is a transient/parse failure, not an explicit reject —
        // only AR should be treated as "PMS rejected this message".
        assertFalse(isRejectAck(ackWith("AE")))
    }

    @Test
    fun `isRejectAck false when no MSA segment present`() {
        assertFalse(isRejectAck("MSH|^~\\&|PMS|PMSFAC|APP|APPFAC|20240101120000||ACK|CTRL1|P|2.5"))
    }

    @Test
    fun `isRejectAck false for blank input`() {
        assertFalse(isRejectAck(""))
    }

    @Test
    fun `isRejectAck reads MSA from a line split by newline as well as carriage return`() {
        val ack = "MSH|^~\\&|PMS|PMSFAC|APP|APPFAC|20240101120000||ACK|CTRL2|P|2.5\n" +
            "MSA|AR|CTRL2"
        assertTrue(isRejectAck(ack))
    }
}
