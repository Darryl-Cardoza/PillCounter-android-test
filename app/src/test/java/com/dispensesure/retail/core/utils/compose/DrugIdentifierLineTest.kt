package com.dispensesure.retail.core.utils.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class DrugIdentifierLineTest {

    private fun row(
        rxNo: String? = null,
        refillNo: String? = null,
        ndc: String? = null,
        bucketId: String? = null,
        drugType: String? = null,
    ) = DrugCountRowData(
        barcodeImage = null,
        ndc = ndc,
        drugType = drugType,
        drugName = "Drug",
        date = "",
        bucketId = bucketId,
        pillCount = 0,
        targetCount = 0,
        isDispense = true,
        rxNo = rxNo,
        refillNo = refillNo,
    )

    private fun DrugCountRowData.line() = buildIdentifierLine(rxLabel = { "Rx $it" }, ndcLabel = { "NDC $it" })

    @Test
    fun `rx with refill bucket and schedule`() {
        assertEquals(
            "Rx 7654321-2  •  340B  •  CII",
            row(rxNo = "7654321", refillNo = "2", ndc = "123", bucketId = "340B", drugType = "cii").line(),
        )
    }

    @Test
    fun `rx without refill`() {
        assertEquals("Rx 7654321", row(rxNo = "7654321", refillNo = " ").line())
    }

    @Test
    fun `ndc stands in when there is no rx number`() {
        assertEquals("NDC 123  •  340B", row(rxNo = " ", ndc = "123", bucketId = "340B").line())
    }

    @Test
    fun `ndc and bucket are trimmed like the rx number`() {
        assertEquals("NDC 123  •  340B", row(ndc = " 123 ", bucketId = " 340B ").line())
    }

    @Test
    fun `non dea drug type and blank bucket are skipped`() {
        assertEquals("Rx 1", row(rxNo = "1", bucketId = " ", drugType = "BRAND").line())
    }

    @Test
    fun `nothing present gives an empty line`() {
        assertEquals("", row().line())
    }
}
