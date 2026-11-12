package com.dispensesure.retail.feature.hl7.order

import org.junit.Assert.assertEquals
import org.junit.Test
import org.rite.hl7.model.segment.ZDRSegment
import org.rite.hl7.parser.HL7ParseResult
import org.rite.hl7.parser.HL7Parser

class NativeOrderKeyTest {

    private val parser = HL7Parser.Builder().build()

    private fun zdr(orderRef: String, rx: String, fill: String): ZDRSegment {
        val raw = "MSH|^~\\&|PMS|FAC|APP|STORE|20240101120000||RDE^O11|MSG-1|P|2.3.1\r" +
            "ZDR|D|1.0|MSG-1|NW|12345678901^DRUG A|NDC|DOE^JANE|||PAT|||AB|$rx|$orderRef|30|$fill|R|5|A"
        val message = when (val r = parser.parse(raw)) {
            is HL7ParseResult.Success -> r.message
            is HL7ParseResult.Failure -> r.partialMessage ?: error("parse failed: ${r.errors}")
        }
        return message.segment<ZDRSegment>("ZDR")!!
    }

    @Test fun `blank null and zero fill numbers all normalize to 0`() {
        listOf(null, "", "   ", "0", "00", "000").forEach {
            assertEquals("0", NativeOrderKey.normalizedFillNo(it))
        }
    }

    @Test fun `leading zeros are stripped from the fill number`() =
        assertEquals("3", NativeOrderKey.normalizedFillNo("003"))

    @Test fun `interior zeros are preserved`() =
        assertEquals("10", NativeOrderKey.normalizedFillNo("010"))

    @Test fun `compose joins rx and normalized fill with a hyphen`() =
        assertEquals("RX123-2", NativeOrderKey.compose("RX123", "02"))

    @Test fun `compose trims rx and maps empty fill to 0`() =
        assertEquals("RX123-0", NativeOrderKey.compose("  RX123 ", ""))

    @Test fun `resend with different first-fill spellings yields same key`() {
        val keys = listOf("", "0", "00").map { NativeOrderKey.compose("RX9", it) }.toSet()
        assertEquals(setOf("RX9-0"), keys)
    }

    @Test fun `resolve prefers a non blank ZDR-15`() =
        assertEquals("ORD-9", NativeOrderKey.resolve(zdr(orderRef = "ORD-9", rx = "RX1", fill = "1")))

    @Test fun `resolve falls back to rx and fill when ZDR-15 is blank`() =
        assertEquals("RX1-1", NativeOrderKey.resolve(zdr(orderRef = "  ", rx = "RX1", fill = "1")))

    @Test fun `resolve fallback normalizes empty fill`() =
        assertEquals("RX1-0", NativeOrderKey.resolve(zdr(orderRef = "", rx = "RX1", fill = "")))
}
