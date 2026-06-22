package com.rite.pillcounting.feature.hl7.domain.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Hl7MessageTypeTest {

    @Test
    fun `entries contain exactly the expected message types`() {
        val expected = arrayOf(
            MessageType.DISPENSE_REQUEST,
            MessageType.EDIT_DISPENSE_REQUEST,
            MessageType.INVENTORY_REQUEST,
            MessageType.CANCEL_ORDER
        )
        assertArrayEquals(expected, MessageType.values())
        assertEquals(4, MessageType.values().size)
    }

    @Test
    fun `valueOf resolves each declared name`() {
        assertEquals(MessageType.DISPENSE_REQUEST, MessageType.valueOf("DISPENSE_REQUEST"))
        assertEquals(MessageType.EDIT_DISPENSE_REQUEST, MessageType.valueOf("EDIT_DISPENSE_REQUEST"))
        assertEquals(MessageType.INVENTORY_REQUEST, MessageType.valueOf("INVENTORY_REQUEST"))
        assertEquals(MessageType.CANCEL_ORDER, MessageType.valueOf("CANCEL_ORDER"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `valueOf throws for unknown name`() {
        MessageType.valueOf("UNKNOWN")
    }

    @Test
    fun `ordinals are stable`() {
        assertEquals(0, MessageType.DISPENSE_REQUEST.ordinal)
        assertEquals(1, MessageType.EDIT_DISPENSE_REQUEST.ordinal)
        assertEquals(2, MessageType.INVENTORY_REQUEST.ordinal)
        assertEquals(3, MessageType.CANCEL_ORDER.ordinal)
    }
}
