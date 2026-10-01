package com.dispensesure.retail.feature.hl7.parsing

import android.util.Log
import com.dispensesure.retail.core.room.models.enums.TxnPriority
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.rite.hl7.parser.HL7ParseResult
import org.rite.hl7.parser.HL7Parser

/**
 * Unit tests for [Hl7Parser].
 *
 * RXE field positions (1-indexed, library reads 1-based):
 *   f2  = give code/name CWE  (giveCode = component(2,1), giveName = component(2,2))
 *   f3  = give amount minimum (giveAmountMinimum = fieldValue(3))
 *   f10 = dispense amount      (dispenseAmount   = fieldValue(10))
 *   f12 = number of refills    (numberOfRefills  = fieldValue(12))
 *   f15 = prescription number  (prescriptionNumber = fieldValue(15))
 *   f16 = pending refills      (pendingRefills   = raw.fieldValue(16))
 *
 * Format: RXE|f1|f2|f3|f4|f5|f6|f7|f8|f9|f10|f11|f12|f13|f14|f15|f16
 */
class Hl7ParserTest {

    private val hl7Parser = HL7Parser.Builder().build()
    private val parser = Hl7Parser()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() = unmockkAll()

    private fun parse(raw: String) = when (val r = hl7Parser.parse(raw)) {
        is HL7ParseResult.Success -> r.message
        is HL7ParseResult.Failure -> r.partialMessage ?: error("parse failed: ${r.errors}")
    }

    // RXE fields: f1=|f2=NDC^name|f3-f9=empty|f10=qty|f11=|f12=refills|f13|f14|f15=prescNo|f16=pending
    private val NW_RAW = buildString {
        append("MSH|^~\\&|PMS|FAC|APP|STORE|20240101120000||RDE^O11|CTRL-1|P|2.5\r")
        append("ORC|NW|ORD-001|RX-001\r")
        append("RXE||70000012501^METOPROLOL TAR 50MG TAB^NDC|||||||||50||3|||RX-001|2\r")
    }

    @Test
    fun `map single NW order returns one OrderGroup with correct fields`() {
        val msg = parse(NW_RAW)
        val result = parser.map(msg)

        assertTrue(result.isSuccess)
        val groups = result.getOrThrow()
        assertEquals(1, groups.size)
        val g = groups[0]
        assertEquals("NW", g.orderControl)
        assertEquals("ORD-001", g.placerOrderNumber)
        assertEquals("RX-001", g.fillerOrderNumber)
        assertEquals("70000012501", g.giveCode)
        assertEquals("METOPROLOL TAR 50MG TAB", g.giveName)
        assertEquals("RX-001", g.prescriptionNumber)
    }

    @Test
    fun `map multiple ORCs with RXE each returns one OrderGroup per ORC`() {
        val raw = buildString {
            append("MSH|^~\\&|PMS|FAC|APP|STORE|20240101120000||RDE^O11|CTRL-2|P|2.5\r")
            append("ORC|NW|ORD-A|RX-A\r")
            append("RXE||NDC-A^DRUG-A^NDC|||||||||||0|||RX-A\r")
            append("ORC|NW|ORD-B|RX-B\r")
            append("RXE||NDC-B^DRUG-B^NDC|||||||||||0|||RX-B\r")
        }
        val result = parser.map(parse(raw))

        assertTrue(result.isSuccess)
        val groups = result.getOrThrow()
        assertEquals(2, groups.size)
        assertEquals("ORD-A", groups[0].placerOrderNumber)
        assertEquals("ORD-B", groups[1].placerOrderNumber)
    }

    @Test
    fun `map without ORC returns failure`() {
        val raw = "MSH|^~\\&|PMS|FAC|APP|STORE|20240101||RDE^O11|CTRL-3|P|2.5\r"
        val result = parser.map(parse(raw))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `NW ORC without RXE is skipped — all skipped returns failure`() {
        val raw = buildString {
            append("MSH|^~\\&|PMS|FAC|APP|STORE|20240101||RDE^O11|CTRL-4|P|2.5\r")
            append("ORC|NW|ORD-NW|RX-NW\r")
            // No RXE — NW requires RXE, so this ORC is skipped → failure
        }
        val result = parser.map(parse(raw))
        assertTrue(result.isFailure)
    }

    @Test
    fun `CA order without RXE succeeds — cancel does not need drug info`() {
        val raw = buildString {
            append("MSH|^~\\&|PMS|FAC|APP|STORE|20240101||RDE^O11|CTRL-CA|P|2.5\r")
            append("ORC|CA|ORD-CA|RX-CA\r")
        }
        val result = parser.map(parse(raw))
        assertTrue(result.isSuccess)
        val groups = result.getOrThrow()
        assertEquals("CA", groups[0].orderControl)
        assertNull(groups[0].giveCode)
        assertNull(groups[0].giveName)
        assertEquals("ORD-CA", groups[0].placerOrderNumber)
    }

    @Test
    fun `HD and DC orders without RXE succeed`() {
        listOf("HD", "DC", "RL").forEach { control ->
            val raw = buildString {
                append("MSH|^~\\&|PMS|FAC|APP|STORE|20240101||RDE^O11|CTRL-$control|P|2.5\r")
                append("ORC|$control|ORD-$control|RX-$control\r")
            }
            val result = parser.map(parse(raw))
            assertTrue("$control should parse OK without RXE", result.isSuccess)
            assertEquals(control, result.getOrThrow()[0].orderControl)
        }
    }

    @Test
    fun `missing priority defaults to Medium`() {
        val raw = buildString {
            append("MSH|^~\\&|PMS|FAC|APP|STORE|20240101||RDE^O11|CTRL-DEF|P|2.5\r")
            append("ORC|CA|ORD-DEF|RX-DEF\r")
        }
        val groups = parser.map(parse(raw)).getOrThrow()
        assertEquals(TxnPriority.Medium, groups[0].priority)
    }

    @Test
    fun `refillNumber is 0 when RXE has no refill fields`() {
        val raw = buildString {
            append("MSH|^~\\&|PMS|FAC|APP|STORE|20240101||RDE^O11|CTRL-RF0|P|2.5\r")
            append("ORC|NW|ORD-RF0|RX-RF0\r")
            append("RXE||NDC-RF0^DRUG-RF0^NDC\r")
        }
        val groups = parser.map(parse(raw)).getOrThrow()
        assertEquals(0, groups[0].refillNumber)
        assertNull(groups[0].numberOfRefills)
        assertNull(groups[0].pendingRefills)
    }

    @Test
    fun `prescriptionNumber falls back to fillerOrderNumber when RXE-15 blank`() {
        val raw = buildString {
            append("MSH|^~\\&|PMS|FAC|APP|STORE|20240101||RDE^O11|CTRL-PN|P|2.5\r")
            append("ORC|NW|ORD-PN|FILLER-PN\r")
            append("RXE||NDC-PN^DRUG-PN^NDC\r")
        }
        val groups = parser.map(parse(raw)).getOrThrow()
        // RXE-15 blank → falls back to fillerOrderNumber
        assertEquals("FILLER-PN", groups[0].prescriptionNumber)
    }
}
