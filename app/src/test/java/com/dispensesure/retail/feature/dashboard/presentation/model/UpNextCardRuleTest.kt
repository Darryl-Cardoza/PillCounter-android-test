package com.dispensesure.retail.feature.dashboard.presentation.model

import com.dispensesure.retail.core.room.models.dtos.BatchSummaryDto
import com.dispensesure.retail.core.room.models.dtos.PillCountWithDrugAndTotal
import com.dispensesure.retail.core.room.models.enums.TxnPriority
import com.dispensesure.retail.feature.dashboard.domain.model.QueueItem
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class UpNextCardRuleTest {

    private val dispense = QueueItem.Dispense(
        txn = PillCountWithDrugAndTotal(
            txnId = 1L,
            drugName = "Aspirin",
            ndc = "12345-678-90",
            drugType = "OTC",
            bucketId = null,
            createdAt = 1000L,
            targetCount = 30,
            bottleInfoListJson = "img",
            totalPillCount = 10,
            isComingFromHL7 = false,
            isNdcVerified = true,
            isDispense = true,
            priority = TxnPriority.High,
            isHazardous = false,
        ),
        isHazardous = false,
        isHighPriority = false,
        isControlled = false,
    )

    private val inventory = QueueItem.Inventory(
        batch = BatchSummaryDto(
            batchId = 5L,
            createdAt = 2000L,
            uniqueNdcCount = 3,
            status = "INPROGRESS",
            bucketId = null,
            requestIdFromPMS = null,
        ),
    )

    @Test
    fun returnsFirstItemWhenItIsDispense() {
        assertSame(dispense, upNextDispense(listOf(dispense, inventory)))
    }

    @Test
    fun nullWhenFirstItemIsInventory() {
        assertNull(upNextDispense(listOf(inventory, dispense)))
    }

    @Test
    fun nullWhenListIsEmpty() {
        assertNull(upNextDispense(emptyList()))
    }
}
