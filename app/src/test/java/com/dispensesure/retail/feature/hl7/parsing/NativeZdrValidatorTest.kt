package com.dispensesure.retail.feature.hl7.parsing

import com.dispensesure.retail.core.region.Region
import com.dispensesure.retail.core.room.models.DispenseTxnEntity
import com.dispensesure.retail.core.room.models.enums.CountStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.rite.hl7.model.HL7Message
import org.rite.hl7.model.segment.ZDRSegment
import org.rite.hl7.parser.HL7ParseResult
import org.rite.hl7.parser.HL7Parser

class NativeZdrValidatorTest {

    private val parser = HL7Parser.Builder().build()

    /** Builds a raw message; ZDR positions: 1 flow, 2 layout, 3 reqId, 4 action, 5 drugs, 6 codeType, 14 rx, 16 qty, 19 refills. */
    private fun message(
        flow: String = "D",
        layout: String = "1.0",
        requestMessageId: String = "MSG1",
        mshControlId: String = "MSG1",
        mshDateTime: String = "20240101120000",
        action: String = "NW",
        drugs: List<String> = listOf("00093-0058-01"),
        codeType: String = "NDC",
        rx: String = "RX1",
        qty: String = "30",
        refillsAllowed: String = "",
        extraZdr: Int = 0,
    ): HL7Message {
        val zdr = listOf(
            "ZDR", flow, layout, requestMessageId, action, drugs.joinToString("~") { "$it^DRUG" }, codeType,
            "DOE^JANE", "", "", "PAT", "", "", "AB", rx, "ORD-1", qty, "1", "R", refillsAllowed,
        ).joinToString("|")
        val raw = "MSH|^~\\&|PMS|FAC|APP|STORE|$mshDateTime||RDE^O11|$mshControlId|P|2.3.1\r" +
            List(1 + extraZdr) { zdr }.joinToString("\r")
        return when (val r = parser.parse(raw)) {
            is HL7ParseResult.Success -> r.message
            is HL7ParseResult.Failure -> r.partialMessage ?: error("parse failed: ${r.errors}")
        }
    }

    private fun zdr(drugs: List<String>) = message(drugs = drugs).segment<ZDRSegment>("ZDR")!!

    private fun txn(status: CountStatus, isDeleted: Boolean = false) =
        DispenseTxnEntity(status = status, isDeleted = isDeleted)

    private fun validate(
        flow: String = "D",
        layout: String = "1.0",
        requestMessageId: String = "MSG1",
        mshControlId: String = "MSG1",
        mshDateTime: String = "20240101120000",
        action: String = "NW",
        drugs: List<String> = listOf("00093-0058-01"),
        codeType: String = "NDC",
        rx: String = "RX1",
        qty: String = "30",
        refillsAllowed: String = "",
        extraZdr: Int = 0,
        existing: DispenseTxnEntity? = null,
        fillCount: Int = 0,
        region: Region = Region.US,
    ) = NativeZdrValidator.validate(
        message(flow, layout, requestMessageId, mshControlId, mshDateTime, action, drugs, codeType, rx, qty, refillsAllowed, extraZdr),
        existing, fillCount, region,
    )

    @Test fun `fixture parses ZDR fields at the expected positions`() {
        val z = message(rx = "RXZ", qty = "7", refillsAllowed = "4", action = "RF").segment<ZDRSegment>("ZDR")!!
        assertEquals("RXZ", z.rxNumber)
        assertEquals("7", z.quantityToDispense)
        assertEquals("4", z.refillsAllowed)
        assertEquals("NDC", z.drugCodeType)
        assertEquals("RF", z.orderAction)
    }

    @Test fun `a dispense ZDR carrying two drugs is rejected`() =
        assertEquals(
            NativeRejectReason.MULTIPLE_DRUGS_ON_DISPENSE,
            validate(flow = "D", drugs = listOf("00093-0058-01", "00054-0018-25")),
        )

    @Test fun `a single drug dispense ZDR is accepted`() =
        assertNull(validate(flow = "D", action = "NW", drugs = listOf("00093-0058-01")))

    @Test fun `an inventory ZDR may carry many drugs`() =
        assertNull(validate(flow = "I", drugs = listOf("00093-0058-01", "00054-0018-25")))

    @Test fun `inventory drug codes are deduplicated preserving order`() =
        assertEquals(listOf("A", "B"), NativeZdrValidator.dedupedDrugCodes(zdr(drugs = listOf("A", "B", "A"))))

    @Test fun `a ZDR-03 that does not match MSH-10 is rejected`() =
        assertEquals(
            NativeRejectReason.REQUEST_ID_MISMATCH,
            validate(requestMessageId = "MSG1", mshControlId = "MSG2"),
        )

    @Test fun `two ZDR segments are rejected`() =
        assertEquals(NativeRejectReason.MULTIPLE_ZDR_SEGMENTS, validate(extraZdr = 1))

    @Test fun `a blank MSH-7 is rejected`() =
        assertEquals(NativeRejectReason.MISSING_ENVELOPE_FIELD, validate(mshDateTime = ""))

    @Test fun `an unknown flow type is rejected`() =
        assertEquals(NativeRejectReason.INVALID_FLOW_TYPE, validate(flow = "X"))

    @Test fun `an unknown layout version is rejected`() =
        assertEquals(NativeRejectReason.INVALID_LAYOUT_VERSION, validate(layout = "9.9"))

    @Test fun `a quantity of zero is rejected on a new order`() =
        assertEquals(NativeRejectReason.INVALID_QUANTITY, validate(action = "NW", qty = "0"))

    @Test fun `a missing quantity is rejected on a new order`() =
        assertEquals(NativeRejectReason.INVALID_QUANTITY, validate(action = "NW", qty = ""))

    @Test fun `a non numeric quantity is rejected`() =
        assertEquals(NativeRejectReason.INVALID_QUANTITY, validate(qty = "abc"))

    @Test fun `a quantity above 9999 is rejected`() =
        assertEquals(NativeRejectReason.INVALID_QUANTITY, validate(qty = "10000"))

    @Test fun `a quantity of 9999 is accepted`() = assertNull(validate(qty = "9999"))

    @Test fun `a missing Rx number is rejected`() =
        assertEquals(NativeRejectReason.MISSING_RX_NUMBER, validate(rx = ""))

    @Test fun `a US drug code with the wrong digit count is rejected`() =
        assertEquals(NativeRejectReason.INVALID_DRUG_CODE, validate(drugs = listOf("1234567")))

    @Test fun `a US 11 digit NDC is accepted`() =
        assertNull(validate(drugs = listOf("00093-058-011")))

    @Test fun `a Canadian 8 digit DIN is accepted in Canada`() =
        assertNull(validate(drugs = listOf("02345678"), codeType = "DIN", region = Region.CA))

    @Test fun `an NDC code type is rejected in Canada`() =
        assertEquals(
            NativeRejectReason.INVALID_DRUG_CODE,
            validate(drugs = listOf("02345678"), codeType = "NDC", region = Region.CA),
        )

    @Test fun `a 10 digit NDC is rejected in Canada`() =
        assertEquals(
            NativeRejectReason.INVALID_DRUG_CODE,
            validate(drugs = listOf("0009300581"), codeType = "", region = Region.CA),
        )

    @Test fun `XO against a completed order is rejected`() =
        assertEquals(
            NativeRejectReason.ORDER_ALREADY_COMPLETED,
            validate(action = "XO", existing = txn(CountStatus.COMPLETED)),
        )

    @Test fun `XO against a missing order is rejected`() =
        assertEquals(NativeRejectReason.ORDER_NOT_FOUND, validate(action = "XO", existing = null))

    @Test fun `XO against a pending order is accepted`() =
        assertNull(validate(action = "XO", existing = txn(CountStatus.PARTIAL)))

    @Test fun `XO against a discontinued order is rejected`() =
        assertEquals(
            NativeRejectReason.ORDER_ALREADY_COMPLETED,
            validate(action = "XO", existing = txn(CountStatus.PARTIAL, isDeleted = true)),
        )

    @Test fun `CA against a completed order is accepted`() =
        assertNull(validate(action = "CA", existing = txn(CountStatus.COMPLETED)))

    @Test fun `CA against an already cancelled order is rejected`() =
        assertEquals(
            NativeRejectReason.ORDER_ALREADY_COMPLETED,
            validate(action = "CA", existing = txn(CountStatus.PARTIAL, isDeleted = true)),
        )

    @Test fun `HD against a completed order is rejected`() =
        assertEquals(
            NativeRejectReason.ORDER_ALREADY_COMPLETED,
            validate(action = "HD", existing = txn(CountStatus.COMPLETED)),
        )

    @Test fun `DC against a missing order is rejected`() =
        assertEquals(NativeRejectReason.ORDER_NOT_FOUND, validate(action = "DC", existing = null))

    @Test fun `RL against an order that is not on hold is rejected`() =
        assertEquals(
            NativeRejectReason.ORDER_NOT_ON_HOLD,
            validate(action = "RL", existing = txn(CountStatus.PARTIAL)),
        )

    @Test fun `RL against an order on hold is accepted`() =
        assertNull(validate(action = "RL", existing = txn(CountStatus.ON_HOLD)))

    @Test fun `a refill beyond the authorized count is rejected`() =
        assertEquals(
            NativeRejectReason.NO_REFILLS_REMAINING,
            validate(action = "RF", refillsAllowed = "2", fillCount = 3),
        )

    @Test fun `a refill within the authorized count is accepted`() =
        assertNull(validate(action = "RF", refillsAllowed = "2", fillCount = 2))

    @Test fun `a refill with blank refills allowed is rejected`() =
        assertEquals(
            NativeRejectReason.NO_REFILLS_REMAINING,
            validate(action = "RF", refillsAllowed = "", fillCount = 1),
        )

    @Test fun `a refill with no prior fill is rejected as not found`() =
        assertEquals(
            NativeRejectReason.ORDER_NOT_FOUND,
            validate(action = "RF", refillsAllowed = "2", fillCount = 0),
        )

    @Test fun `a resent NW for a completed order is accepted and creates a new fill`() =
        assertNull(validate(action = "NW", existing = txn(CountStatus.COMPLETED)))

    @Test fun `RP is rejected as unsupported`() =
        assertEquals(NativeRejectReason.REPLACE_NOT_SUPPORTED, validate(action = "RP"))

    @Test fun `an unknown order action is rejected`() =
        assertEquals(NativeRejectReason.UNSUPPORTED_ORDER_ACTION, validate(action = "ZZ"))

    @Test fun `a blank order action on dispense is rejected`() =
        assertEquals(NativeRejectReason.UNSUPPORTED_ORDER_ACTION, validate(action = ""))
}
