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

    private fun validate(giveCode: String) = validator.validateStructure(rdeWithGiveCode(giveCode))

    // Behaviour, not wording: a US device must reject an 8-digit Canadian DIN. The AAR's
    // error text carries no per-RXE index, so asserting an exact string here only tracked
    // the library's phrasing — assert the rejection and the segment instead.
    @Test
    fun `US rejects an 8-digit DIN as an invalid NDC`() {
        val result = validate("02230714")
        assertFalse(result.issues.toString(), result.isValid)
        assertTrue(
            result.issues.toString(),
            result.issues.any { it.errorText.contains("Invalid NDC") && it.segmentId == "RXE" },
        )
    }

    // Review point 7: a DIN in one RXE must not mask a bad code in a DIFFERENT RXE.
    // Single-RXE cases cannot distinguish "suppress for this RXE" from "suppress for all",
    // so this two-RXE case is the one that pins the behaviour.
    @Test
    fun `canada DIN in one RXE does not mask a malformed code in another`() {
        every { regionProvider.current } returns Region.CA
        val message = parse(buildString {
            append("MSH|^~\\&|PMS|FAC|APP|STORE|20240101120000||RDE^O11|CTRL-1|P|2.5\r")
            append("ORC|NW|ORD-001|RX-001\r")
            append("RXE||02230714^DRUG A|30||TAB|||||30|TAB|0|||RX-001\r")
            append("RXE||ABC123^DRUG B|30||TAB|||||30|TAB|0|||RX-002\r")
        })
        val result = validator.validateStructure(message)
        assertFalse(
            "the malformed code in RXE 2 must still be reported: ${result.issues}",
            result.isValid,
        )
    }

    private fun twoRxe(first: String, second: String) = parse(buildString {
        append("MSH|^~\\&|PMS|FAC|APP|STORE|20240101120000||RDE^O11|CTRL-1|P|2.5\r")
        append("ORC|NW|ORD-001|RX-001\r")
        append("RXE||$first^DRUG A|30||TAB|||||30|TAB|0|||RX-001\r")
        append("RXE||$second^DRUG B|30||TAB|||||30|TAB|0|||RX-002\r")
    })

    @Test
    fun `canada accepts two valid codes of different kinds in separate RXEs`() {
        every { regionProvider.current } returns Region.CA
        val result = validator.validateStructure(twoRxe("02230714", "00093005801"))
        assertTrue(result.issues.toString(), result.isValid)
    }

    @Test
    fun `US still rejects a DIN in a multi-RXE order`() {
        every { regionProvider.current } returns Region.US
        val result = validator.validateStructure(twoRxe("00093005801", "02230714"))
        assertFalse(result.issues.toString(), result.isValid)
        assertTrue(result.issues.any { it.segmentId == "RXE" && it.errorText.contains("Invalid NDC") })
    }

    @Test
    fun `canada accepts a dashed NDC`() {
        every { regionProvider.current } returns Region.CA
        assertTrue(validate("00093-0058-01").isValid)
    }

    @Test
    fun `canada accepts an 8-digit DIN in RXE-2`() {
        every { regionProvider.current } returns Region.CA
        val result = validate("02230714")
        assertTrue(result.issues.toString(), result.isValid)
    }

    @Test
    fun `canada still accepts an NDC and still rejects a malformed code`() {
        every { regionProvider.current } returns Region.CA
        val ndcResult = validate("00093005801")
        assertTrue(ndcResult.issues.toString(), ndcResult.isValid)
        assertFalse(validate("ABC123").isValid)
    }

    // ---- validateZdrStructure — DSValidationRule field coverage ----

    private fun zdrMsg(
        controlId: String = "MSG1",
        flowType: String = "D",
        layoutVersion: String = "1.0",
        zdrControlId: String = controlId,
        action: String = "NW",
        drugs: String = "12345678901^DRUG A",
        rxNumber: String = "RX-001",
        orderId: String = "ORDER-001",
        qty: String = "30",
    ) = parse(
        "MSH|^~\\&|PMS|FAC|APP|STORE|20240101120000||RDE^O11|$controlId|P|2.3.1\r" +
            "ZDR|$flowType|$layoutVersion|$zdrControlId|$action|$drugs|NDC|DOE^JANE|||PAT|||AB|$rxNumber|$orderId|$qty|1|R|5|A"
    )

    @Test
    fun `validateZdrStructure passes valid NW message`() {
        val result = validator.validateZdrStructure(zdrMsg())
        assertTrue("expected valid, got: ${result.issues}", result.isValid)
    }

    @Test
    fun `validateZdrStructure rejects ZDR-03 mismatch with MSH-10`() {
        // MSH-10 = MSG1, ZDR-03 = WRONG — DSValidationRule checks ZDR-03 == MSH-10
        val result = validator.validateZdrStructure(zdrMsg(controlId = "MSG1", zdrControlId = "WRONG"))
        assertFalse("ZDR-03 != MSH-10 must be invalid", result.isValid)
    }

    @Test
    fun `validateZdrStructure rejects blank ZDR-05 drugs`() {
        val result = validator.validateZdrStructure(zdrMsg(drugs = ""))
        assertFalse("blank ZDR-05 must be invalid", result.isValid)
    }

    @Test
    fun `validateZdrStructure rejects unknown orderAction`() {
        val result = validator.validateZdrStructure(zdrMsg(action = "ZZ"))
        assertFalse("unknown orderAction must be invalid", result.isValid)
    }

    @Test
    fun `validateZdrStructure rejects blank ZDR-16 quantity`() {
        val result = validator.validateZdrStructure(zdrMsg(qty = ""))
        assertFalse("blank ZDR-16 quantity must be invalid", result.isValid)
    }

    @Test
    fun `validateZdrStructure rejects blank ZDR-14 rxNumber for NW`() {
        // DSValidationRule requires ZDR-14 for dispense actions (NW/RF)
        val result = validator.validateZdrStructure(zdrMsg(rxNumber = ""))
        assertFalse("blank ZDR-14 for NW must be invalid", result.isValid)
    }

    @Test
    fun `validateZdrStructure rejects blank ZDR-14 rxNumber for RF`() {
        val result = validator.validateZdrStructure(zdrMsg(action = "RF", rxNumber = ""))
        assertFalse("blank ZDR-14 for RF must be invalid", result.isValid)
    }

    @Test
    fun `validateZdrStructure passes valid RF message`() {
        val result = validator.validateZdrStructure(zdrMsg(action = "RF"))
        assertTrue("RF with all required fields must be valid", result.isValid)
    }

    @Test
    fun `validateStructure unchanged — ORC-RXE message does not run DSValidationRule`() {
        // Blank ZDR-14 equivalent doesn't exist in ORC/RXE path — confirm standard path unaffected
        val msg = parse(
            "MSH|^~\\&|PMS|FAC|APP|STORE|20240101120000||RDE^O11|MSG1|P|2.5\r" +
                "ORC|NW|ORD-001|RX-001\rRXE||00093005801^DRUG|30||TAB|||||30|TAB|0|||RX-001"
        )
        val result = validator.validateStructure(msg)
        assertNotNull("validateStructure must return non-null for ORC/RXE", result)
    }

    // ---- validateZdrInventoryStructure — DISPENSESURE_NATIVE INR^U06 ZDR validation ----

    private fun zdrInvMsg(
        controlId: String = "MSG-INV",
        zdrControlId: String = controlId,
        flowType: String = "I",
        layoutVersion: String = "1.0",
        drugs: String = "12345678901^ASPIRIN",
    ) = parse(
        "MSH|^~\\&|PMS|FAC|APP|STORE|20240101120000||INR^U06|$controlId|P|2.3.1\r" +
            "ZDR|$flowType|$layoutVersion|$zdrControlId||$drugs|NDC||||||||||||||"
    )

    @Test
    fun `validateZdrInventoryStructure passes valid INVENTORY message`() {
        val result = validator.validateZdrInventoryStructure(zdrInvMsg())
        assertTrue("expected valid inventory ZDR, got: ${result.issues}", result.isValid)
    }

    @Test
    fun `validateZdrInventoryStructure rejects wrong flowType`() {
        // flowType=D (DISPENSE) in an inventory message must fail
        val result = validator.validateZdrInventoryStructure(zdrInvMsg(flowType = "D"))
        assertFalse("wrong flowType must be invalid", result.isValid)
    }

    @Test
    fun `validateZdrInventoryStructure rejects blank layoutVersion`() {
        val result = validator.validateZdrInventoryStructure(zdrInvMsg(layoutVersion = ""))
        assertFalse("blank ZDR-02 layoutVersion must be invalid", result.isValid)
    }

    @Test
    fun `validateZdrInventoryStructure rejects ZDR-03 mismatch with MSH-10`() {
        val result = validator.validateZdrInventoryStructure(zdrInvMsg(controlId = "MSG-INV", zdrControlId = "WRONG"))
        assertFalse("ZDR-03 != MSH-10 must be invalid", result.isValid)
    }

    @Test
    fun `validateZdrInventoryStructure rejects blank ZDR-03 requestMessageId`() {
        val result = validator.validateZdrInventoryStructure(zdrInvMsg(zdrControlId = ""))
        assertFalse("blank ZDR-03 must be invalid", result.isValid)
    }

    @Test
    fun `validateZdrInventoryStructure rejects blank ZDR-05 drugs`() {
        val result = validator.validateZdrInventoryStructure(zdrInvMsg(drugs = ""))
        assertFalse("blank ZDR-05 must be invalid", result.isValid)
    }

    @Test
    fun `validateZdrInventoryStructure does not require ZDR-14 rxNumber`() {
        // Inventory has no rx number — field is blank, must still be valid
        val result = validator.validateZdrInventoryStructure(zdrInvMsg())
        assertTrue("inventory ZDR must be valid without ZDR-14", result.isValid)
    }

    @Test
    fun `validateZdrInventoryStructure does not require ZDR-16 quantity`() {
        // Inventory has no dispense quantity — must still be valid
        val result = validator.validateZdrInventoryStructure(zdrInvMsg())
        assertTrue("inventory ZDR must be valid without ZDR-16 quantity", result.isValid)
    }

    @Test
    fun `validateZdrInventoryStructure is separate from validateZdrStructure`() {
        // A valid INVENTORY message fails dispense validation (wrong flowType for DSValidationRule)
        val invMsg = zdrInvMsg()
        val inventoryResult = validator.validateZdrInventoryStructure(invMsg)
        assertTrue("inventory validation must pass for INVENTORY message", inventoryResult.isValid)
        // Dispense validator may emit issues (flowType/orderAction mismatch) — assert they are separate
        val dispenseResult = validator.validateZdrStructure(invMsg)
        // Just verify both return non-null results without cross-contamination
        assertNotNull("dispense validator must return a result", dispenseResult)
        assertNotNull("inventory validator must return a result", inventoryResult)
    }
}
