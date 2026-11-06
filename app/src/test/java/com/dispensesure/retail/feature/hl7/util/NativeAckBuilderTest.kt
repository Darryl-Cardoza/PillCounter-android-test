package com.dispensesure.retail.feature.hl7.util

import com.dispensesure.retail.feature.hl7.parsing.NativeRejectReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.rite.hl7.model.HL7Message
import org.rite.hl7.parser.HL7ParseResult
import org.rite.hl7.parser.HL7Parser

@RunWith(Parameterized::class)
class NativeAckBuilderTest(private val version: String) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "HL7 {0}")
        fun versions() = listOf("2.3.1", "2.5.1")
    }

    private val parser = HL7Parser.Builder().build()

    private fun inboundWithControlId(id: String): HL7Message {
        val raw = "MSH|^~\\&|PMS|FAC|APP|STORE|20240101||RDE^O11|$id|P|2.5"
        return when (val r = parser.parse(raw)) {
            is HL7ParseResult.Success -> r.message
            is HL7ParseResult.Failure -> r.partialMessage ?: error("parse failed: ${r.errors}")
        }
    }

    private val inbound get() = inboundWithControlId("MSG-1")

    private fun segmentIds(ack: String) =
        ack.split('\r', '\n').filter { it.isNotBlank() }.map { it.take(3) }

    private fun msa(ack: String) =
        ack.split('\r', '\n').first { it.startsWith("MSA|") }.split("|")

    @Test fun `an accepted native ACK carries only MSH and MSA`() {
        assertEquals(listOf("MSH", "MSA"), segmentIds(NativeAckBuilder.build(inbound, null, version)))
    }

    @Test fun `a rejected native ACK carries only MSH and MSA`() {
        val ack = NativeAckBuilder.build(inbound, NativeRejectReason.ORDER_NOT_FOUND, version)
        assertEquals(listOf("MSH", "MSA"), segmentIds(ack))
    }

    @Test fun `an accepted native ACK uses AA`() {
        assertTrue(NativeAckBuilder.build(inbound, null, version).contains("MSA|AA|"))
    }

    @Test fun `a rejected native ACK uses AR and carries the reason in MSA-3`() {
        val fields = msa(NativeAckBuilder.build(inbound, NativeRejectReason.ORDER_NOT_FOUND, version))
        assertEquals("AR", fields[1])
        assertEquals(NativeRejectReason.ORDER_NOT_FOUND.code, fields[3])
    }

    @Test fun `the ACK echoes the inbound message control id`() {
        val fields = msa(NativeAckBuilder.build(inboundWithControlId("MSG-42"), null, version))
        assertEquals("MSG-42", fields[2])
    }

    @Test fun `a rejected ACK echoes the control id too`() {
        val fields = msa(NativeAckBuilder.build(inboundWithControlId("MSG-42"), NativeRejectReason.NO_ZDR_SEGMENT, version))
        assertEquals("MSG-42", fields[2])
    }

    @Test fun `an accepted ACK has no reason field`() {
        assertTrue(msa(NativeAckBuilder.build(inbound, null, version)).getOrElse(3) { "" }.isEmpty())
    }
}
