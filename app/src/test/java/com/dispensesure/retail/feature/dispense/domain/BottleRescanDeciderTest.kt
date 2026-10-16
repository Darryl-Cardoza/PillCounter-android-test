package com.dispensesure.retail.feature.dispense.domain

import com.dispensesure.retail.core.scanning.domain.model.DispenseBottle
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [BottleRescanDecider]. */
class BottleRescanDeciderTest {

    private val firstBottle = DispenseBottle(lotNumber = "L1", expirationDate = "04-30-2028", serialNumber = "S1")
    private val secondBottle = DispenseBottle(lotNumber = "L2", expirationDate = "05-31-2028", serialNumber = "S2")

    @Test
    fun `the last bottle's GS1 values again is already scanned`() {
        assertEquals(
            RescanOutcome.AlreadyScanned,
            BottleRescanDecider.decide(listOf(firstBottle), firstBottle.copy(scannedAt = 1L), isGs1 = true, countedSoFar = 5),
        )
    }

    @Test
    fun `a plain barcode is never already scanned`() {
        assertEquals(
            RescanOutcome.AskAddNew,
            BottleRescanDecider.decide(listOf(DispenseBottle()), DispenseBottle(), isGs1 = false, countedSoFar = 5),
        )
        assertEquals(
            RescanOutcome.AskReplace,
            BottleRescanDecider.decide(listOf(DispenseBottle()), DispenseBottle(), isGs1 = false, countedSoFar = 0),
        )
    }

    @Test
    fun `a new bottle after pills were counted is added`() {
        assertEquals(
            RescanOutcome.AskAddNew,
            BottleRescanDecider.decide(listOf(firstBottle), secondBottle, isGs1 = true, countedSoFar = 5),
        )
    }

    @Test
    fun `a new bottle before anything was counted replaces the last one`() {
        assertEquals(
            RescanOutcome.AskReplace,
            BottleRescanDecider.decide(listOf(firstBottle), secondBottle, isGs1 = true, countedSoFar = 0),
        )
    }

    @Test
    fun `the first bottle on file is always added`() {
        assertEquals(
            RescanOutcome.AskAddNew,
            BottleRescanDecider.decide(emptyList(), firstBottle, isGs1 = true, countedSoFar = 0),
        )
    }

    @Test
    fun `an earlier bottle scanned again counts as a new one`() {
        assertEquals(
            RescanOutcome.AskAddNew,
            BottleRescanDecider.decide(listOf(firstBottle, secondBottle), firstBottle, isGs1 = true, countedSoFar = 5),
        )
    }
}
