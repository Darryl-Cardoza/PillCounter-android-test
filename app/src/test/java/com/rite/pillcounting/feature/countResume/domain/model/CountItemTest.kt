package com.rite.pillcounting.feature.countResume.domain.model

import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.room.models.enums.TxnPriority
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
        pillCount: Int = 30,
        target: Int = 60,
        bottleInfoListJson: String? = "barcode.png",
        date: String = "2026-06-17",
        image: Int = R.drawable.logo,
        isComingFromHL7: Boolean = false,
        isNdcVerified: Boolean = true,
        isDispense: Boolean = false,
        priority: TxnPriority? = null
    ) = CountItem(
        id = id,
        name = name,
        ndc = ndc,
        drugType = drugType,
        bucketId = bucketId,
        pillCount = pillCount,
        target = target,
        bottleInfoListJson = bottleInfoListJson,
        date = date,
        image = image,
        isComingFromHL7 = isComingFromHL7,
        isNdcVerified = isNdcVerified,
        isDispense = isDispense,
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
            pillCount = 30,
            target = 60,
            bottleInfoListJson = "barcode.png",
            date = "2026-06-17",
            isComingFromHL7 = false,
            isNdcVerified = true
        )

        assertEquals(R.drawable.logo, item.image)
        assertEquals(false, item.isDispense)
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
            pillCount = 12,
            target = 24,
            bottleInfoListJson = null,
            date = "2026-01-01",
            image = R.drawable.logo,
            isComingFromHL7 = true,
            isNdcVerified = false,
            isDispense = true,
            priority = TxnPriority.High
        )

        assertEquals(99L, item.id)
        assertEquals("Ibuprofen", item.name)
        assertNull(item.ndc)
        assertNull(item.drugType)
        assertNull(item.bucketId)
        assertEquals(12, item.pillCount)
        assertEquals(24, item.target)
        assertNull(item.bottleInfoListJson)
        assertEquals("2026-01-01", item.date)
        assertEquals(R.drawable.logo, item.image)
        assertTrue(item.isComingFromHL7)
        assertEquals(false, item.isNdcVerified)
        assertEquals(true, item.isDispense)
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
        val copy = original.copy(pillCount = 100, priority = TxnPriority.Medium)

        assertEquals(100, copy.pillCount)
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
            pillCount = 7,
            target = 14,
            bottleInfoListJson = "img",
            date = "date",
            image = R.drawable.logo,
            isComingFromHL7 = true,
            isNdcVerified = false,
            isDispense = true,
            priority = TxnPriority.Medium
        )

        val (id, name, ndc, drugType, bucketId, pillCount, target, barcodeImage,
            date, image, isComingFromHL7, isNdcVerified, isDispense, priority) = item

        assertEquals(5L, id)
        assertEquals("Drug", name)
        assertEquals("ndc", ndc)
        assertEquals("type", drugType)
        assertEquals("bucket", bucketId)
        assertEquals(7, pillCount)
        assertEquals(14, target)
        assertEquals("img", barcodeImage)
        assertEquals("date", date)
        assertEquals(R.drawable.logo, image)
        assertEquals(true, isComingFromHL7)
        assertEquals(false, isNdcVerified)
        assertEquals(true, isDispense)
        assertEquals(TxnPriority.Medium, priority)
    }
}
