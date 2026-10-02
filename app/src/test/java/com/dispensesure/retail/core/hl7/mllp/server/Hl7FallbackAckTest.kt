package com.dispensesure.retail.core.hl7.mllp.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Hl7FallbackAckTest {

    private fun sampleHl7(controlId: String = "MSG001", procId: String = "P", version: String = "2.5"): String =
        "MSH|^~\\&|SENDAPP|SENDFAC|RECVAPP|RECVFAC|20240101120000||ADT^A01|$controlId|$procId|$version\r" +
            "PID|1||12345^^^MRN||DOE^JOHN||19800101|M"

    @Test
    fun `swaps sending and receiving app and facility, AA when no error`() {
        val raw = sampleHl7("CTRL123")

        val ack = Hl7FallbackAck.build(raw, null)

        val mshFields = ack.lineSequence().first { it.startsWith("MSH|") }.split("|")
        // sendingApp/fac <-> receivingApp/fac are swapped relative to the original.
        assertEquals("RECVAPP", mshFields.getOrNull(2))
        assertEquals("RECVFAC", mshFields.getOrNull(3))
        assertEquals("SENDAPP", mshFields.getOrNull(4))
        assertEquals("SENDFAC", mshFields.getOrNull(5))
        assertTrue(ack.contains("MSA|AA|CTRL123"))
    }

    @Test
    fun `echoes inbound processing id and version instead of hardcoding them`() {
        val raw = sampleHl7("CTRL321", procId = "T", version = "2.3")

        val ack = Hl7FallbackAck.build(raw, "boom")

        val mshFields = ack.lineSequence().first { it.startsWith("MSH|") }.split("|")
        assertEquals("ACKCTRL321", mshFields[9])
        assertEquals("T", mshFields[10])
        assertEquals("2.3", mshFields[11])
    }

    @Test
    fun `returns AR ack code when errorMsg provided`() {
        val raw = sampleHl7("CTRL456")

        val ack = Hl7FallbackAck.build(raw, "Something went wrong")

        assertTrue(ack.contains("MSA|AR|CTRL456|Something went wrong"))
    }

    @Test
    fun `sanitizes pipe and newline characters in error message`() {
        val raw = sampleHl7("CTRL789")

        val ack = Hl7FallbackAck.build(raw, "bad|pipe\r\nchars")

        // The error text must not introduce extra pipe-delimited fields or line breaks
        // into the MSA segment, since that would corrupt the ACK's field structure.
        val msaLine = ack.lineSequence().first { it.startsWith("MSA|") }
        assertFalse(msaLine.contains("\r"))
        assertFalse(msaLine.contains("\n"))
        val msaFields = msaLine.split("|")
        assertEquals("AR", msaFields[1])
        assertEquals("CTRL789", msaFields[2])
        assertEquals("bad pipe  chars", msaFields[3])
    }

    @Test
    fun `falls into catch branch and still returns ack when no MSH segment present`() {
        val raw = "GARBAGE_NO_MSH_SEGMENT_AT_ALL"

        val ack = Hl7FallbackAck.build(raw, "parse failed")

        assertTrue(ack.startsWith("MSH|"))
        assertTrue(ack.contains("MSA|AR"))
    }

    @Test
    fun `catch branch produces AA ack when no error and no MSH`() {
        val raw = ""

        val ack = Hl7FallbackAck.build(raw, null)

        assertTrue(ack.contains("MSA|AA"))
    }
}
