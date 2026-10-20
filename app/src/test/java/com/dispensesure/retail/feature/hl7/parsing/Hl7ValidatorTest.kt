package com.dispensesure.retail.feature.hl7.parsing

import android.util.Log
import com.dispensesure.retail.core.region.Region
import com.dispensesure.retail.core.region.RegionProvider
import com.dispensesure.retail.core.room.models.enums.TxnPriority
import com.dispensesure.retail.feature.hl7.parsing.model.OrderGroup
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.rite.hl7.parser.HL7ParseResult
import org.rite.hl7.parser.HL7Parser

class Hl7ValidatorTest {

    private val hl7Parser = HL7Parser.Builder().build()
    private val regionProvider: RegionProvider = mockk { every { current } returns Region.US }
    private val validator = Hl7Validator(regionProvider)

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

    private fun orderGroup(
        orderControl: String = "NW",
        pendingRefills: Int? = null,
        numberOfRefills: Int? = null,
    ) = OrderGroup(
        messageControlId = "CTRL",
        sendingApplication = "PMS",
        sendingFacility = "FAC",
        receivingApplication = "APP",
        receivingFacility = "STORE",
        messageDateTime = "20240101",
        orderControl = orderControl,
        placerOrderNumber = "ORD-1",
        fillerOrderNumber = "RX-1",
        orderStatus = null,
        priority = TxnPriority.Medium,
        giveCode = "12345678901",
        giveName = "DRUG",
        dispenseAmount = 30,
        giveUnits = "EA",
        substitutionStatus = null,
        numberOfRefills = numberOfRefills,
        prescriptionNumber = "RX-1",
        pendingRefills = pendingRefills,
        refillNumber = 0,
    )

    // ---- validateStructure ----

    @Test
    fun `validateStructure returns a ValidationResult without throwing`() {
        val raw = buildString {
            append("MSH|^~\\&|PMS|FAC|APP|STORE|20240101120000||RDE^O11|CTRL-1|P|2.5\r")
            append("ORC|NW|ORD-001|RX-001\r")
        }
        // Just confirm the call completes and returns a non-null result;
        // the library's exact issue set is an implementation detail not under test here.
        val result = validator.validateStructure(parse(raw))
        assertNotNull(result)
    }

    // ---- validateBusinessRules ----

    @Test
    fun `RF with positive pendingRefills is valid`() {
        val result = validator.validateBusinessRules(orderGroup("RF", pendingRefills = 2, numberOfRefills = 3))
        assertTrue(result.isValid)
        assertNull(result.reason)
    }

    @Test
    fun `RF with zero pendingRefills is invalid`() {
        val result = validator.validateBusinessRules(orderGroup("RF", pendingRefills = 0, numberOfRefills = 3))
        assertFalse(result.isValid)
        assertNotNull(result.reason)
        assertTrue(result.reason!!.contains("RXE-16=0"))
    }

    @Test
    fun `RF with negative pendingRefills is invalid`() {
        val result = validator.validateBusinessRules(orderGroup("RF", pendingRefills = -1, numberOfRefills = 3))
        assertFalse(result.isValid)
    }

    @Test
    fun `RF with null pendingRefills is valid (no data = no block)`() {
        val result = validator.validateBusinessRules(orderGroup("RF", pendingRefills = null))
        assertTrue(result.isValid)
    }

    @Test
    fun `NW order is always valid regardless of pendingRefills`() {
        val result = validator.validateBusinessRules(orderGroup("NW", pendingRefills = 0))
        assertTrue(result.isValid)
    }

    @Test
    fun `CA order is always valid`() {
        val result = validator.validateBusinessRules(orderGroup("CA"))
        assertTrue(result.isValid)
    }

    @Test
    fun `HD hold order is always valid`() {
        val result = validator.validateBusinessRules(orderGroup("HD"))
        assertTrue(result.isValid)
    }

    // RDE with one RXE whose RXE-2 is [giveCode]; the other required RXE fields are filled in.
    private fun rdeWithGiveCode(giveCode: String) = parse(buildString {
        append("MSH|^~\\&|PMS|FAC|APP|STORE|20240101120000||RDE^O11|CTRL-1|P|2.5\r")
        append("ORC|NW|ORD-001|RX-001\r")
        append("RXE||$giveCode^LIPITOR 40MG TABLET|30||TAB|||||30|TAB|0|||RX-001\r")
    })

    private fun invalidNdcIssues(giveCode: String) =
        validator.validateStructure(rdeWithGiveCode(giveCode)).issues
            .filter { issue -> issue.errorText.startsWith("Invalid NDC") }

    @Test
    fun `US rejects an 8-digit DIN as an invalid NDC`() {
        assertEquals(1, invalidNdcIssues("02230714").size)
    }

    @Test
    fun `canada accepts an 8-digit DIN in RXE-2`() {
        every { regionProvider.current } returns Region.CA
        assertEquals(emptyList<Any>(), invalidNdcIssues("02230714"))
    }

    @Test
    fun `canada still accepts an NDC and still rejects a malformed code`() {
        every { regionProvider.current } returns Region.CA
        assertEquals(emptyList<Any>(), invalidNdcIssues("00093005801"))
        assertEquals(1, invalidNdcIssues("ABC123").size)
    }
}
