package com.dispensesure.retail.feature.unsyncedTransaction.domain.model

import com.dispensesure.retail.R
import com.dispensesure.retail.core.room.models.enums.TxnPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CountItemTest {

    private fun sample(
        id: Long = 1L,
        name: String = "Amoxicillin",
        ndc: String? = "12345-678-90",
        drugType: String? = "Capsule",
        bucketId: String? = "bucket-1",
        target: Int = 60,
        bottlesJson: String? = "barcode.png",
        date: String = "2026-06-17",
        image: Int = R.drawable.logo,
        isComingFromHL7: Boolean = false,
        isNdcVerified: Boolean = true,
        priority: TxnPriority? = null
    ) = CountItem(
        id = id,
        name = name,
        ndc = ndc,
        drugType = drugType,
        bucketId = bucketId,
        target = target,
        bottlesJson = bottlesJson,
        date = date,
        image = image,
        isComingFromHL7 = isComingFromHL7,
        isNdcVerified = isNdcVerified,
        priority = priority
    )

    @Test
    fun `default params apply when omitted`() {
        val item = CountItem(
            id = 1L,
            name = "Amoxicillin",
            ndc = "12345-678-90",
            drugType = "Capsule",
            bucketId = "bucket-1",
            target = 60,
            bottlesJson = "barcode.png",
            date = "2026-06-17",
            isComingFromHL7 = false,
            isNdcVerified = true
        )

        assertEquals(R.drawable.logo, item.image)
        assertNull(item.priority)
    }

    @Test
    fun `all properties expose constructor values`() {
        val item = sample(
            id = 99L,
            name = "Ibuprofen",
            ndc = null,
            drugType = null,
            bucketId = null,
            target = 24,
            bottlesJson = null,
            date = "2026-01-01",
            image = R.drawable.logo,
            isComingFromHL7 = true,
            isNdcVerified = false,
            priority = TxnPriority.High
        )

        assertEquals(99L, item.id)
        assertEquals("Ibuprofen", item.name)
        assertNull(item.ndc)
        assertNull(item.drugType)
        assertNull(item.bucketId)
        assertEquals(24, item.target)
        assertNull(item.bottlesJson)
        assertEquals("2026-01-01", item.date)
        assertEquals(R.drawable.logo, item.image)
        assertTrue(item.isComingFromHL7)
        assertEquals(false, item.isNdcVerified)
        assertEquals(TxnPriority.High, item.priority)
    }

    @Test
    fun `equals and hashCode reflect value semantics`() {
        val a = sample()
        val b = sample()
        val c = sample(name = "Different")

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun `toString contains property values`() {
        val text = sample().toString()
        assertTrue(text.contains("Amoxicillin"))
        assertTrue(text.contains("CountItem"))
    }

    @Test
    fun `copy overrides selected fields and keeps others`() {
        val original = sample(priority = TxnPriority.Low)
        val copy = original.copy(target = 100, priority = TxnPriority.Medium)

        assertEquals(100, copy.target)
        assertEquals(TxnPriority.Medium, copy.priority)
        assertEquals(original.name, copy.name)
        assertEquals(original.id, copy.id)
    }

    @Test
    fun `componentN destructuring returns all fields in order`() {
        val item = sample(
            id = 5L,
            name = "Drug",
            ndc = "ndc",
            drugType = "type",
            bucketId = "bucket",
            target = 14,
            bottlesJson = "img",
            date = "date",
            image = R.drawable.logo,
            isComingFromHL7 = true,
            isNdcVerified = false,
            priority = TxnPriority.Medium
        )

        val (id, name, ndc, drugType, bucketId, target, barcodeImage,
            date, image, isComingFromHL7, isNdcVerified, priority) = item

        assertEquals(5L, id)
        assertEquals("Drug", name)
        assertEquals("ndc", ndc)
        assertEquals("type", drugType)
        assertEquals("bucket", bucketId)
        assertEquals(14, target)
        assertEquals("img", barcodeImage)
        assertEquals("date", date)
        assertEquals(R.drawable.logo, image)
        assertEquals(true, isComingFromHL7)
        assertEquals(false, isNdcVerified)
        assertEquals(TxnPriority.Medium, priority)
    }
}
